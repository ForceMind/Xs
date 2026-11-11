package dev.forcemind.xs.android;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Device-bound pending-transfer URLs only. This key and ciphertext are excluded from exports. */
final class XsDownloadSecrets {
    private static final String ALIAS="Xs.pending-download-url.v1";
    private static synchronized SecretKey key() throws GeneralSecurityException {
        try {
            KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
            if(store.containsAlias(ALIAS))return (SecretKey)store.getKey(ALIAS,null);
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setKeySize(256).build());
            return generator.generateKey();
        }catch(java.io.IOException failure){throw new GeneralSecurityException("Download key unavailable");}
    }
    static String seal(String value,String scope)throws GeneralSecurityException {
        if(value==null||value.length()>16384)throw new GeneralSecurityException("Invalid media reference");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        if(cipher.getIV().length!=12)throw new GeneralSecurityException("Unsupported download nonce");
        cipher.updateAAD(scope.getBytes(StandardCharsets.UTF_8));byte[] ciphertext=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] output=new byte[12+ciphertext.length];System.arraycopy(cipher.getIV(),0,output,0,12);System.arraycopy(ciphertext,0,output,12,ciphertext.length);
        return Base64.encodeToString(output,Base64.NO_WRAP);
    }
    static String open(String sealed,String scope)throws GeneralSecurityException {
        try {
            if(sealed==null||sealed.length()>32768)throw new GeneralSecurityException("Missing media reference");
            byte[] bytes=Base64.decode(sealed,Base64.NO_WRAP);if(bytes.length<28)throw new GeneralSecurityException("Invalid media reference");
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Arrays.copyOf(bytes,12)));
            cipher.updateAAD(scope.getBytes(StandardCharsets.UTF_8));return new String(cipher.doFinal(bytes,12,bytes.length-12),StandardCharsets.UTF_8);
        }catch(IllegalArgumentException failure){throw new GeneralSecurityException("Invalid media reference");}
    }
}
