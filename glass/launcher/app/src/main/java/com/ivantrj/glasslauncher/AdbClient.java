package com.ivantrj.glasslauncher;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * Just enough of the ADB protocol to run one shell command on this TV over its own
 * network debugging port (5555), reached at the TV's own Wi-Fi address: Fire OS adbd
 * hangs up on loopback (127.0.0.1) connections. Glass uses it once per boot to start the
 * Injector with shell rights. The first time, the TV asks "Allow USB debugging?" for
 * Glass's key; with "Always allow" ticked it never asks again.
 */
final class AdbClient {

    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f,
            OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000000, MAX_DATA = 4096;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    /** ASN.1 DigestInfo header for SHA-1: adbd signs its 20-byte token as if it were a SHA-1 digest. */
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    /**
     * Runs a shell command and returns its output. Waits up to approveMs for the TV's
     * "Allow USB debugging?" prompt the first time.
     */
    static String shell(Context context, String host, String command, int approveMs) throws IOException {
        KeyPair keys = keys(context);
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, 5555), 3000);
            s.setSoTimeout(approveMs);
            DataInputStream in = new DataInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            send(out, CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));
            boolean signed = false;
            while (true) {
                Msg m = read(in);
                if (m.cmd == CNXN) break;
                if (m.cmd != AUTH || m.arg0 != AUTH_TOKEN) throw new IOException("unexpected adb reply");
                if (!signed) {
                    send(out, AUTH, AUTH_SIGNATURE, 0, sign(keys.getPrivate(), m.data));
                    signed = true;
                } else { // key not trusted yet: offer it, the TV shows its prompt
                    send(out, AUTH, AUTH_RSAPUBLICKEY, 0, publicKey((RSAPublicKey) keys.getPublic()));
                }
            }
            s.setSoTimeout(10_000);
            int local = 1;
            send(out, OPEN, local, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            while (true) {
                Msg m = read(in);
                if (m.cmd == WRTE) {
                    output.write(m.data);
                    send(out, OKAY, local, m.arg0, new byte[0]);
                } else if (m.cmd == CLSE) {
                    send(out, CLSE, local, m.arg0, new byte[0]);
                    return output.toString("UTF-8");
                }
            }
        }
    }

    private static void send(OutputStream out, int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int sum = 0;
        for (byte b : data) sum += b & 0xFF;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~cmd);
        out.write(h.array());
        out.write(data);
        out.flush();
    }

    private static Msg read(DataInputStream in) throws IOException {
        byte[] head = new byte[24];
        in.readFully(head);
        ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = h.getInt();
        m.arg0 = h.getInt();
        m.arg1 = h.getInt();
        m.data = new byte[h.getInt()];
        in.readFully(m.data);
        return m;
    }

    /** PKCS#1 v1.5 signature of the token, done as a raw RSA private-key operation. */
    private static byte[] sign(PrivateKey key, byte[] token) throws IOException {
        try {
            byte[] block = new byte[256];
            block[0] = 0;
            block[1] = 1;
            int pad = block.length - 3 - SHA1_PREFIX.length - token.length;
            for (int i = 0; i < pad; i++) block[2 + i] = (byte) 0xFF;
            block[2 + pad] = 0;
            System.arraycopy(SHA1_PREFIX, 0, block, 3 + pad, SHA1_PREFIX.length);
            System.arraycopy(token, 0, block, 3 + pad + SHA1_PREFIX.length, token.length);
            Cipher c = Cipher.getInstance("RSA/ECB/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key);
            return c.doFinal(block);
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    /** The key in adbd's format: Android's RSAPublicKey struct, base64, then a name. */
    private static byte[] publicKey(RSAPublicKey key) {
        BigInteger n = key.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.remainder(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64).putInt(n0inv.intValue());
        putWords(b, n);
        putWords(b, rr);
        b.putInt(key.getPublicExponent().intValue());
        String text = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " glass@tv\0";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void putWords(ByteBuffer b, BigInteger v) {
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        for (int i = 0; i < 64; i++) {
            b.putInt(v.remainder(r32).intValue());
            v = v.shiftRight(32);
        }
    }

    /** Glass's own key pair, made once and kept in its private files. */
    private static synchronized KeyPair keys(Context context) throws IOException {
        File priv = new File(context.getFilesDir(), "adbkey"), pub = new File(context.getFilesDir(), "adbkey.pub");
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            if (priv.isFile() && pub.isFile()) {
                return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pub.toPath()))),
                        kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(priv.toPath()))));
            }
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair kp = g.generateKeyPair();
            Files.write(priv.toPath(), kp.getPrivate().getEncoded());
            Files.write(pub.toPath(), kp.getPublic().getEncoded());
            return kp;
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    private AdbClient() {}
}
