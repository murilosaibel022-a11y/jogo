package com.nexusia.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Código de pareamento guardado com Android Keystore AES-GCM. */
final class SecretStore {
    private static final String ALIAS="NexusIA_One_Pairing";
    private final SharedPreferences prefs;
    SecretStore(Context context){prefs=context.getSharedPreferences("nexus_secure_link",Context.MODE_PRIVATE);}
    String host(){return prefs.getString("host","");}
    void setHost(String host){prefs.edit().putString("host",host).apply();}
    private SecretKey key()throws Exception{
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias(ALIAS)){
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey)store.getKey(ALIAS,null);
    }
    void saveToken(String token)throws Exception{
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        prefs.edit().putString("token_iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP))
            .putString("token_cipher",Base64.encodeToString(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP)).apply();
    }
    String token()throws Exception{
        String iv=prefs.getString("token_iv",""),c=prefs.getString("token_cipher","");
        if(iv.isEmpty()||c.isEmpty())return "";
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(c,Base64.NO_WRAP)),StandardCharsets.UTF_8);
    }
    void clear(){prefs.edit().clear().apply();}
}
