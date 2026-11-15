#include <jni.h>
#include <algorithm>
#include <cstdint>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include "llama.h"

// Keep the GGUF weights resident, but free the KV cache after every response.
static std::mutex g_mutex;
static llama_model *g_model=nullptr;
static std::string g_loaded;
static bool g_initialized=false;

static jbyteArray to_java(JNIEnv *env,const std::string& s){
    jbyteArray arr=env->NewByteArray((jsize)s.size());
    if(arr)env->SetByteArrayRegion(arr,0,(jsize)s.size(),reinterpret_cast<const jbyte*>(s.data()));
    return arr;
}
static void set_batch(llama_batch_ext *batch,const llama_token *tokens,int n,int pos,bool with_logits){
    llama_batch_ext_clear(batch);
    for(int i=0;i<n;i++){
        int32_t index=llama_batch_ext_add_token(batch,0,tokens[i]);
        llama_pos p=pos+i;
        llama_batch_ext_set_pos(batch,index,&p);
    }
    if(with_logits)llama_batch_ext_set_output_logits(batch,n-1,true);
}

static std::string infer(const std::string &path,const std::string &prompt,int maxTokens,int nThreads){
    if(!g_initialized){llama_backend_init();g_initialized=true;}
    if(!g_model||path!=g_loaded){
        if(g_model){llama_model_free(g_model);g_model=nullptr;}
        llama_model_params mp=llama_model_default_params();
        mp.n_gpu_layers=0; // battery/thermal-safe portable baseline for ARM64
        mp.use_mmap=true;
        g_model=llama_model_load_from_file(path.c_str(),mp);
        if(!g_model)throw std::runtime_error("Não foi possível carregar o modelo GGUF.");
        g_loaded=path;
    }
    const llama_vocab *vocab=llama_model_get_vocab(g_model);
    if(!vocab)throw std::runtime_error("Vocabulário GGUF indisponível.");

    int estimated=llama_tokenize(vocab,prompt.c_str(),prompt.size(),nullptr,0,true,true);
    int cap=estimated<0?-estimated:estimated;
    if(cap<1||cap>3800)throw std::runtime_error("Mensagem muito longa para a memória do celular.");
    std::vector<llama_token> tokens(cap+16);
    int count=llama_tokenize(vocab,prompt.c_str(),prompt.size(),tokens.data(),tokens.size(),true,true);
    if(count<=0)throw std::runtime_error("Não foi possível converter a mensagem em tokens.");
    tokens.resize((size_t)count);
    constexpr int CTX=3072;
    constexpr int BATCH=256;
    int maxReply=std::max(64,std::min(maxTokens,280));
    if((int)tokens.size()>CTX-maxReply-6)
        throw std::runtime_error("A conversa está longa demais. Reduza o texto ou inicie outra conversa.");
    llama_context_params cp=llama_context_default_params();
    cp.n_ctx=CTX;cp.n_batch=BATCH;cp.n_ubatch=128;
    cp.n_threads=std::max(1,std::min(nThreads,6));
    cp.n_threads_batch=cp.n_threads;
    cp.no_perf=true;
    llama_context *ctx=llama_init_from_model(g_model,cp);
    if(!ctx)throw std::runtime_error("Memória insuficiente para executar a IA.");
    llama_sampler *sampler=nullptr;
    llama_batch_ext *batch=nullptr;
    std::string output;
    try{
        auto sp=llama_sampler_chain_default_params();
        sp.no_perf=true;
        sampler=llama_sampler_chain_init(sp);
        llama_sampler_chain_add(sampler,llama_sampler_init_top_k(32));
        llama_sampler_chain_add(sampler,llama_sampler_init_top_p(0.88f,1));
        llama_sampler_chain_add(sampler,llama_sampler_init_temp(0.65f));
        llama_sampler_chain_add(sampler,llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
        batch=llama_batch_ext_init(ctx);
        if(!batch)throw std::runtime_error("Falha ao preparar contexto.");
        int pos=0;
        for(int off=0;off<count;){
            int n=std::min(BATCH,count-off);
            set_batch(batch,tokens.data()+off,n,pos,off+n==count);
            if(llama_process(ctx,LLAMA_PROCESS_TYPE_DECODE,batch)!=0)
                throw std::runtime_error("Falha ao processar o texto no motor local.");
            off+=n;pos+=n;
        }
        // The last prompt batch generated logits. Each sampled token generates the next logits.
        for(int i=0;i<maxReply;i++){
            llama_token id=llama_sampler_sample(sampler,ctx,-1);
            if(llama_vocab_is_eog(vocab,id))break;
            char piece[1024];
            int n=llama_token_to_piece(vocab,id,piece,sizeof(piece),0,true);
            if(n>0)output.append(piece,(size_t)n);
            if(pos>=CTX-1)break;
            set_batch(batch,&id,1,pos,true);
            if(llama_process(ctx,LLAMA_PROCESS_TYPE_DECODE,batch)!=0)
                throw std::runtime_error("A geração foi interrompida pelo motor local.");
            ++pos;
        }
    }catch(...){
        if(batch)llama_batch_ext_free(batch);
        if(sampler)llama_sampler_free(sampler);
        llama_free(ctx);
        throw;
    }
    llama_batch_ext_free(batch);llama_sampler_free(sampler);llama_free(ctx);
    return output;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_nexusia_mobile_NativeEngine_nativeInfer(JNIEnv *env,jclass,jstring jPath,jstring jPrompt,jint maxTokens,jint nThreads){
    if(!jPath||!jPrompt)return to_java(env,"Erro: entrada inválida.");
    const char *path=env->GetStringUTFChars(jPath,nullptr);
    const char *prompt=env->GetStringUTFChars(jPrompt,nullptr);
    if(!path||!prompt){
        if(path)env->ReleaseStringUTFChars(jPath,path);
        if(prompt)env->ReleaseStringUTFChars(jPrompt,prompt);
        return to_java(env,"Erro de memória ao ler a mensagem.");
    }
    std::string a(path),b(prompt);
    env->ReleaseStringUTFChars(jPath,path);env->ReleaseStringUTFChars(jPrompt,prompt);
    std::lock_guard<std::mutex> guard(g_mutex);
    try{return to_java(env,infer(a,b,maxTokens,nThreads));}
    catch(const std::exception& e){
        jclass type=env->FindClass("java/lang/IllegalStateException");
        if(type)env->ThrowNew(type,e.what());
        return nullptr;
    }catch(...){
        jclass type=env->FindClass("java/lang/IllegalStateException");
        if(type)env->ThrowNew(type,"Erro inesperado no motor nativo.");
        return nullptr;
    }
}
