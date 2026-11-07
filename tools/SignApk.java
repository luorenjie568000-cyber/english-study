import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;

import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 用 apksig 对 APK 做 v1+v2+v3 签名并校验。 */
public class SignApk {

    public static void main(String[] args) throws Exception {
        File ksFile = new File(args[0]);
        String pass = args[1];
        String alias = args[2];
        File in = new File(args[3]);
        File out = new File(args[4]);

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(ksFile)) {
            ks.load(fis, pass.toCharArray());
        }
        PrivateKey key = (PrivateKey) ks.getKey(alias, pass.toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate(alias);

        List<X509Certificate> certs = new ArrayList<>();
        certs.add(cert);

        ApkSigner.SignerConfig sc =
                new ApkSigner.SignerConfig.Builder("CERT", key, certs).build();

        ApkSigner signer = new ApkSigner.Builder(Collections.singletonList(sc))
                .setInputApk(in)
                .setOutputApk(out)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build();
        signer.sign();
        System.out.println("[签名] 完成 -> " + out.getName() + "  (" + out.length() + " 字节)");

        ApkVerifier.Result r = new ApkVerifier.Builder(out).build().verify();
        System.out.println("[校验] 通过=" + r.isVerified()
                + " v1=" + r.isVerifiedUsingV1Scheme()
                + " v2=" + r.isVerifiedUsingV2Scheme()
                + " v3=" + r.isVerifiedUsingV3Scheme());
        if (!r.isVerified()) {
            throw new RuntimeException("签名校验失败");
        }
    }
}
