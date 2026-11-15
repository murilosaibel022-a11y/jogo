package com.nexusia.mobile;

import java.nio.charset.StandardCharsets;

/** Executa um modelo GGUF inteiramente no processador ARM64 do telefone. */
public final class NativeEngine {
    private static final String ERROR;
    static {
        String error="";
        try { System.loadLibrary("nexuslocal"); }
        catch (Throwable t){error=t.getMessage()==null?"Biblioteca indisponível":t.getMessage();}
        ERROR=error;
    }
    private NativeEngine(){}
    public static boolean available(){return ERROR.isEmpty();}
    public static String problem(){return ERROR;}
    private static native byte[] nativeInfer(String file,String prompt,int maxTokens,int threads);
    public static synchronized String generate(String file,String prompt){
        if(!available())throw new IllegalStateException("Motor local indisponível: "+ERROR);
        int cores=Runtime.getRuntime().availableProcessors();
        int threads=Math.max(2,Math.min(4,cores>4?cores-2:cores));
        byte[] result=nativeInfer(file,prompt,220,threads);
        if(result==null||result.length==0)throw new IllegalStateException("O modelo não produziu uma resposta.");
        return new String(result, StandardCharsets.UTF_8).trim();
    }
}
