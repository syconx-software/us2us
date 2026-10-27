import java.nio.file.*;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import org.signal.libsignal.protocol.ServiceId;
import org.signal.libsignal.zkgroup.*;
import org.signal.libsignal.zkgroup.auth.*;
import org.signal.libsignal.zkgroup.groups.*;

class Us2usPrivateGroupProbe {
  static byte[] repeated(int value) { byte[] b=new byte[32];Arrays.fill(b,(byte)value);return b; }
  // Deterministic public fixture generation ONLY. Never use this RNG in application code.
  static SecureRandom fixed(int value) { return new SecureRandom() { public void nextBytes(byte[] b) { Arrays.fill(b,(byte)value); } }; }
  public static void main(String[] args) throws Exception {
    var aci=new ServiceId.Aci(UUID.fromString("11111111-2222-4333-8444-555555555555"));
    var master=new GroupMasterKey(repeated(0x11));
    var group=GroupSecretParams.deriveFromMasterKey(master);
    var server=ServerSecretParams.generate(fixed(0x22));
    var issuer=new ServerZkAuthOperations(server);
    var client=new ClientZkAuthOperations(server.getPublicParams());
    var response=issuer.issueAuthCredentialZkcWithoutPni(fixed(0x33),aci,repeated(0x44),Instant.ofEpochSecond(1728000000L));
    var credential=client.receiveAuthCredentialWithoutPni(aci,repeated(0x44),1728000000L,response);
    var proof=client.createAuthCredentialPresentation(fixed(0x55),group,credential);
    issuer.verifyAuthCredentialPresentation(group.getPublicParams(),proof,Instant.ofEpochSecond(1728003600L));
    var hex=HexFormat.of();var fields=new LinkedHashMap<String,String>();
    fields.put("accountId",aci.getRawUUID().toString());
    fields.put("masterKeyHex",hex.formatHex(master.serialize()));
    fields.put("saltHex",hex.formatHex(repeated(0x44)));
    fields.put("serverSecretParamsHex",hex.formatHex(server.serialize()));
    fields.put("serverPublicParamsHex",hex.formatHex(server.getPublicParams().serialize()));
    fields.put("groupPublicParamsHex",hex.formatHex(group.getPublicParams().serialize()));
    fields.put("memberCiphertextHex",hex.formatHex(new ClientZkGroupCipher(group).encrypt(aci).serialize()));
    fields.put("credentialResponseHex",hex.formatHex(response.serialize()));
    fields.put("presentationHex",hex.formatHex(proof.serialize()));
    StringBuilder out=new StringBuilder("{\n  \"purpose\": \"Synthetic upstream zkgroup probe only; not a BRP wire format or live credentials\",\n  \"libsignalVersion\": \"0.105.0\",\n  \"redemptionTime\": 1728000000,\n  \"verificationTime\": 1728003600");
    for(var e:fields.entrySet())out.append(",\n  \"").append(e.getKey()).append("\": \"").append(e.getValue()).append("\"");
    out.append("\n}\n");
    Files.writeString(Path.of(args[0]),out,StandardOpenOption.CREATE_NEW);
    System.out.println("Synthetic upstream fixture created and proof verified");
  }
}
