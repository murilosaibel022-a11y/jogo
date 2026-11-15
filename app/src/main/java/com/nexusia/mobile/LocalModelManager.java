package com.nexusia.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.StatFs;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Instala uma vez e depois executa a IA totalmente offline. Download retomável + SHA-256. */
public final class LocalModelManager {
    public interface Listener {
        void progress(int percent,String text);
        void ready();
        void error(String message);
    }
    private static final String MODEL="nexus-qwen3-0.6b-q4km.gguf";
    private static final String URL_GGUF="https://huggingface.co/tauqir02/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf?download=true";
    private static final String SHA256="373db06783953f0981572d008f183eb2167b93304f5adbf7c4ce6ed6a91ee437";
    private static final long BYTES=484220288L;
    private final Context app;
    private final SharedPreferences flags;
    private final ExecutorService download=Executors.newSingleThreadExecutor();
    private volatile boolean active=false;
    LocalModelManager(Context context){
        app=context.getApplicationContext();
        flags=app.getSharedPreferences("nexus_model",Context.MODE_PRIVATE);
    }
    File model(){File dir=new File(app.getFilesDir(),"models");dir.mkdirs();return new File(dir,MODEL);}
    String path(){return model().getAbsolutePath();}
    boolean ready(){
        File m=model();
        return m.isFile()&&m.length()==BYTES&&flags.getBoolean("verified",false)&&NativeEngine.available();
    }
    boolean downloading(){return active;}
    long expectedBytes(){return BYTES;}
    void install(Listener listener){
        if(active){listener.error("O download já está em andamento.");return;}
        if(!NativeEngine.available()){listener.error("Este aparelho não suporta o motor ARM64: "+NativeEngine.problem());return;}
        File finalFile=model(),part=new File(finalFile.getParentFile(),MODEL+".part");
        if(finalFile.getUsableSpace()<BYTES+40L*1024*1024-(part.exists()?part.length():0)){
            listener.error("Espaço livre insuficiente para o modelo. São necessários cerca de 500 MB na memória interna do celular. Abra Configurações → Armazenamento para liberar espaço.");return;
        }
        active=true;
        download.execute(()->{
            try{
                if(finalFile.isFile()&&finalFile.length()==BYTES&&verify(finalFile)){
                    flags.edit().putBoolean("verified",true).apply();listener.ready();return;
                }
                flags.edit().putBoolean("verified",false).apply();
                downloadOnce(part,listener);
                if(part.length()!=BYTES)throw new Exception("Download incompleto: "+part.length()+" de "+BYTES+" bytes.");
                listener.progress(99,"Verificando a integridade da IA...");
                if(!verify(part)){part.delete();throw new Exception("Verificação de segurança falhou. O download será reiniciado.");}
                if(finalFile.exists())finalFile.delete();
                if(!part.renameTo(finalFile))throw new Exception("Não foi possível concluir a instalação.");
                flags.edit().putBoolean("verified",true).apply();
                listener.ready();
            }catch(Exception error){
                listener.error(error.getMessage()==null?"Falha inesperada no download.":error.getMessage());
            }finally{active=false;}
        });
    }
    private void downloadOnce(File part,Listener listener)throws Exception{
        long offset=part.exists()?part.length():0;
        if(offset>BYTES){part.delete();offset=0;}
        if(offset==BYTES)return;
        HttpURLConnection conn=(HttpURLConnection)new URL(URL_GGUF).openConnection();
        conn.setConnectTimeout(20000);conn.setReadTimeout(40000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent","NEXUS-IA-Android/0.6");
        if(offset>0)conn.setRequestProperty("Range","bytes="+offset+"-");
        try{
            int status=conn.getResponseCode();
            if(status!=200&&status!=206)throw new Exception("Servidor do modelo indisponível (HTTP "+status+").");
            if(status==206){
                String range=conn.getHeaderField("Content-Range");
                if(range==null||!range.startsWith("bytes "+offset+"-")){
                    throw new Exception("O servidor retornou uma faixa de download inválida.");
                }
            }
            if(status==200&&offset>0){offset=0;part.delete();}
            byte[] data=new byte[64*1024];long total=offset,lastUpdate=0;
            try(InputStream in=new BufferedInputStream(conn.getInputStream(),64*1024);
                FileOutputStream fos=new FileOutputStream(part,offset>0);
                BufferedOutputStream out=new BufferedOutputStream(fos,64*1024)){
                int n;
                while((n=in.read(data))!=-1){
                    total+=n;if(total>BYTES)throw new Exception("O arquivo recebido é maior que o esperado.");
                    out.write(data,0,n);
                    long now=android.os.SystemClock.uptimeMillis();
                    if(now-lastUpdate>400){
                        int pct=(int)(total*98/BYTES);
                        listener.progress(pct,String.format(Locale.getDefault(),"Baixando IA local • %d%% • %.0f/484 MB",pct*100/98,total/1_000_000.0));
                        lastUpdate=now;
                    }
                }
                out.flush();fos.getFD().sync();
            }
        }finally{conn.disconnect();}
    }
    private static boolean verify(File file)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new BufferedInputStream(new java.io.FileInputStream(file),128*1024)){
            byte[] b=new byte[128*1024];int count;
            while((count=in.read(b))!=-1)digest.update(b,0,count);
        }
        StringBuilder sb=new StringBuilder();
        for(byte c:digest.digest())sb.append(String.format(Locale.ROOT,"%02x",c&0xff));
        return SHA256.equals(sb.toString());
    }
}
