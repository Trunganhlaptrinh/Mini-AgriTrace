package bootstrap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import security.PasswordHasher;
import util.DBConnection;

/** Operator-invoked offline commands; this class exposes no HTTP endpoint. */
public final class ConsortiumBootstrapCli {
    private ConsortiumBootstrapCli() { }

    public static void main(String[] args) {
        try {
            if (args.length == 2 && "validate".equals(args[0])) {
                var bundle = new ConsortiumBootstrapService(DBConnection::getConnection, new PasswordHasher())
                        .validate(Files.readString(Path.of(args[1]), StandardCharsets.UTF_8));
                var manifest = BootstrapManifestCodec.read(Files.readString(Path.of(args[1]), StandardCharsets.UTF_8));
                System.out.println("Valid signed bundle: network=" + bundle.network().networkId()
                        + ", genesis=" + bundle.genesis().hash() + ", tip=" + bundle.tipHash()
                        + ", digest=" + BootstrapManifestCodec.digest(manifest));
                return;
            }
            if ((args.length == 2 || args.length == 3) && "status".equals(args[0])) {
                String json = Files.readString(Path.of(args[1]), StandardCharsets.UTF_8);
                var status = new ConsortiumBootstrapService(DBConnection::getConnection, new PasswordHasher())
                        .status(json, args.length == 3 ? args[2] : null);
                System.out.println(new com.google.gson.GsonBuilder().serializeNulls().create().toJson(status));
                return;
            }
            if ((args.length == 2 || args.length == 3) && "signing-bytes".equals(args[0])) {
                var manifest = BootstrapManifestCodec.read(Files.readString(Path.of(args[1]), StandardCharsets.UTF_8));
                byte[] bytes = BootstrapManifestCodec.signingBytes(manifest);
                if (args.length == 3) Files.write(Path.of(args[2]), bytes);
                else System.out.println(Base64.getEncoder().encodeToString(bytes));
                return;
            }
            if ((args.length == 3 || args.length == 5) && "initialize".equals(args[0])) {
                boolean credentialMode = args.length == 5 && "--credential-target".equals(args[3]);
                if (args.length == 5 && !credentialMode)
                    throw new IllegalArgumentException("Unsupported initialize option");
                var console = System.console();
                String json = Files.readString(Path.of(args[1]), StandardCharsets.UTF_8);
                var service = new ConsortiumBootstrapService(DBConnection::getConnection, new PasswordHasher());
                var status = service.status(json, args[2]);
                if (!status.localPeerVerified()) throw new IllegalStateException("Configured local peer identity does not match the signed manifest");
                if (status.state() != BootstrapStatus.State.UNINITIALIZED
                        && status.state() != BootstrapStatus.State.RESUMABLE
                        && status.state() != BootstrapStatus.State.INITIALIZED)
                    throw new IllegalStateException("Bootstrap refused database state " + status.state() + ": " + status.detail());
                if (status.state() == BootstrapStatus.State.INITIALIZED) {
                    System.out.println("Already initialized by this signed manifest: " + status.networkId());
                    return;
                }
                char[] password = null;
                char[] confirmation = null;
                try {
                    if (status.localAdminUsername() == null) {
                        if (credentialMode) {
                            LocalAdminCredentialFlow.initialize(service, json, args[2], args[4],
                                    new WindowsCredentialManager());
                            System.out.println("Bootstrap completed and verified for network " + status.networkId());
                        } else {
                            if (console != null) {
                                password = console.readPassword("New local ADMIN password: ");
                                confirmation = console.readPassword("Confirm local ADMIN password: ");
                            } else {
                                java.io.BufferedReader reader = new java.io.BufferedReader(
                                        new java.io.InputStreamReader(System.in, StandardCharsets.UTF_8));
                                String p1 = reader.readLine();
                                String p2 = reader.readLine();
                                if (p1 == null || p2 == null) {
                                    throw new IllegalStateException("Creating the local ADMIN requires interactive console or password lines on stdin");
                                }
                                password = p1.toCharArray();
                                confirmation = p2.toCharArray();
                            }
                            if (!java.util.Arrays.equals(password, confirmation))
                                throw new IllegalArgumentException("Password confirmation did not match");
                            service.initialize(json, args[2], password);
                            System.out.println("Bootstrap completed and verified for network " + status.networkId());
                        }
                    } else if (credentialMode) {
                        if (!LocalAdminCredentialFlow.targetForUsername(args[2]).equals(args[4]))
                            throw new IllegalArgumentException("Credential target does not match the local ADMIN account");
                        service.initialize(json, args[2], null);
                        System.out.println("Bootstrap completed and verified for network " + status.networkId());
                    } else {
                        service.initialize(json, args[2], null);
                        System.out.println("Bootstrap completed and verified for network " + status.networkId());
                    }
                } finally {
                    if (password != null) java.util.Arrays.fill(password, '\0');
                    if (confirmation != null) java.util.Arrays.fill(confirmation, '\0');
                }
                return;
            }
            throw new IllegalArgumentException("Usage: validate <manifest> | status <manifest> [admin-username] | signing-bytes <manifest> [output-file] | initialize <manifest> <admin-username> [--credential-target <target>]");
        } catch (Exception exception) {
            System.err.println("Bootstrap failed: " + (exception.getMessage() == null ? "invalid input" : exception.getMessage()));
            System.exit(2);
        }
    }
}
