package org.spongycastle.tls.test;

import java.io.IOException;
import java.io.PrintStream;
import java.security.SecureRandom;
import java.util.Hashtable;

import org.spongycastle.asn1.x509.Certificate;
import org.spongycastle.tls.AlertDescription;
import org.spongycastle.tls.AlertLevel;
import org.spongycastle.tls.CertificateRequest;
import org.spongycastle.tls.ChannelBinding;
import org.spongycastle.tls.ClientCertificateType;
import org.spongycastle.tls.DefaultTlsClient;
import org.spongycastle.tls.MaxFragmentLength;
import org.spongycastle.tls.ProtocolVersion;
import org.spongycastle.tls.SignatureAlgorithm;
import org.spongycastle.tls.TlsAuthentication;
import org.spongycastle.tls.TlsCredentials;
import org.spongycastle.tls.TlsExtensionsUtils;
import org.spongycastle.tls.TlsFatalAlert;
import org.spongycastle.tls.TlsServerCertificate;
import org.spongycastle.tls.TlsSession;
import org.spongycastle.tls.crypto.TlsCertificate;
import org.spongycastle.tls.crypto.impl.bc.BcTlsCrypto;
import org.spongycastle.util.Arrays;
import org.spongycastle.util.encoders.Hex;

class MockTlsClient
    extends DefaultTlsClient
{
    TlsSession session;

    MockTlsClient(TlsSession session)
    {
        super(new BcTlsCrypto(new SecureRandom()));

        this.session = session;
    }

    public TlsSession getSessionToResume()
    {
        return this.session;
    }

    public void notifyAlertRaised(short alertLevel, short alertDescription, String message, Throwable cause)
    {
        PrintStream out = (alertLevel == AlertLevel.fatal) ? System.err : System.out;
        out.println("TLS client raised alert: " + AlertLevel.getText(alertLevel)
            + ", " + AlertDescription.getText(alertDescription));
        if (message != null)
        {
            out.println("> " + message);
        }
        if (cause != null)
        {
            cause.printStackTrace(out);
        }
    }

    public void notifyAlertReceived(short alertLevel, short alertDescription)
    {
        PrintStream out = (alertLevel == AlertLevel.fatal) ? System.err : System.out;
        out.println("TLS client received alert: " + AlertLevel.getText(alertLevel)
            + ", " + AlertDescription.getText(alertDescription));
    }

    public Hashtable getClientExtensions() throws IOException
    {
        Hashtable clientExtensions = TlsExtensionsUtils.ensureExtensionsInitialised(super.getClientExtensions());
        {
            /*
             * NOTE: If you are copying test code, do not blindly set these extensions in your own client.
             */
            TlsExtensionsUtils.addMaxFragmentLengthExtension(clientExtensions, MaxFragmentLength.pow2_9);
            TlsExtensionsUtils.addPaddingExtension(clientExtensions, context.getCrypto().getSecureRandom().nextInt(16));
            TlsExtensionsUtils.addTruncatedHMacExtension(clientExtensions);
        }
        return clientExtensions;
    }

    public void notifyServerVersion(ProtocolVersion serverVersion) throws IOException
    {
        super.notifyServerVersion(serverVersion);

        System.out.println("TLS client negotiated " + serverVersion);
    }

    public TlsAuthentication getAuthentication()
        throws IOException
    {
        return new TlsAuthentication()
        {
            public void notifyServerCertificate(TlsServerCertificate serverCertificate)
                throws IOException
            {
                TlsCertificate[] chain = serverCertificate.getCertificate().getCertificateList();

                System.out.println("TLS client received server certificate chain of length " + chain.length);
                for (int i = 0; i != chain.length; i++)
                {
                    Certificate entry = Certificate.getInstance(chain[i].getEncoded());
                    // TODO Create fingerprint based on certificate signature algorithm digest
                    System.out.println("    fingerprint:SHA-256 " + TlsTestUtils.fingerprint(entry) + " ("
                        + entry.getSubject() + ")");
                }

                boolean isEmpty = serverCertificate == null || serverCertificate.getCertificate() == null
                    || serverCertificate.getCertificate().isEmpty();
                if (isEmpty || !TlsTestUtils.isCertificateOneOf(context.getCrypto(), chain[0],
                    new String[]{ "x509-server-dsa.pem", "x509-server-ecdsa.pem", "x509-server-rsa-sign.pem" }))
                {
                    throw new TlsFatalAlert(AlertDescription.bad_certificate);
                }
            }

            public TlsCredentials getClientCredentials(CertificateRequest certificateRequest)
                throws IOException
            {
                short[] certificateTypes = certificateRequest.getCertificateTypes();
                if (certificateTypes == null || !Arrays.contains(certificateTypes, ClientCertificateType.rsa_sign))
                {
                    return null;
                }

                return TlsTestUtils.loadSignerCredentials(context, certificateRequest.getSupportedSignatureAlgorithms(),
                    SignatureAlgorithm.rsa, "x509-client-rsa.pem", "x509-client-key-rsa.pem");
            }
        };
    }

    public void notifyHandshakeComplete() throws IOException
    {
        super.notifyHandshakeComplete();

        byte[] tlsUnique = context.exportChannelBinding(ChannelBinding.tls_unique);
        System.out.println("'tls-unique': " + Hex.toHexString(tlsUnique));

        TlsSession newSession = context.getResumableSession();
        if (newSession != null)
        {
            byte[] newSessionID = newSession.getSessionID();
            String hex = Hex.toHexString(newSessionID);

            if (this.session != null && Arrays.areEqual(this.session.getSessionID(), newSessionID))
            {
                System.out.println("Resumed session: " + hex);
            }
            else
            {
                System.out.println("Established session: " + hex);
            }

            this.session = newSession;
        }
    }
}
