package bootstrap;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.W32APIOptions;
import java.util.Arrays;
import java.util.List;

/** Read-only access to the current Windows user's Generic Credential Manager entries. */
public final class WindowsCredentialManager implements LocalAdminCredentialStore {
    private static final int CRED_TYPE_GENERIC = 1;
    private static final int ERROR_NOT_FOUND = 1168;
    private static final int EXPECTED_SECRET_CHARS = 64;

    private interface Advapi32 extends com.sun.jna.win32.StdCallLibrary {
        Advapi32 INSTANCE = Native.load("Advapi32", Advapi32.class, W32APIOptions.UNICODE_OPTIONS);
        boolean CredReadW(String target, int type, int flags, PointerByReference credential);
        void CredFree(Pointer credential);
    }

    @Structure.FieldOrder({"Flags", "Type", "TargetName", "Comment", "LastWritten",
            "CredentialBlobSize", "CredentialBlob", "Persist", "AttributeCount",
            "Attributes", "TargetAlias", "UserName"})
    public static final class Credential extends Structure {
        public int Flags;
        public int Type;
        public Pointer TargetName;
        public Pointer Comment;
        public byte[] LastWritten = new byte[8];
        public int CredentialBlobSize;
        public Pointer CredentialBlob;
        public int Persist;
        public int AttributeCount;
        public Pointer Attributes;
        public Pointer TargetAlias;
        public Pointer UserName;

        Credential(Pointer pointer) { super(pointer); read(); }

        @Override protected List<String> getFieldOrder() {
            return List.of("Flags", "Type", "TargetName", "Comment", "LastWritten",
                    "CredentialBlobSize", "CredentialBlob", "Persist", "AttributeCount",
                    "Attributes", "TargetAlias", "UserName");
        }
    }

    @Override
    public char[] read(String target) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("windows"))
            throw new IllegalStateException("Windows Credential Manager is available only on Windows");
        if (target == null || !target.matches("AgriTrace/Local3Node/admin-[abc]"))
            throw new IllegalArgumentException("Unsupported local ADMIN credential target");

        PointerByReference result = new PointerByReference();
        if (!Advapi32.INSTANCE.CredReadW(target, CRED_TYPE_GENERIC, 0, result)) {
            int error = Native.getLastError();
            if (error == ERROR_NOT_FOUND) return null;
            throw new IllegalStateException("Could not read the configured Windows credential");
        }
        Pointer credentialPointer = result.getValue();
        if (credentialPointer == null) throw new IllegalStateException("Windows returned an empty credential");
        try {
            Credential credential = new Credential(credentialPointer);
            if (credential.Type != CRED_TYPE_GENERIC || credential.CredentialBlob == null
                    || credential.CredentialBlobSize != EXPECTED_SECRET_CHARS
                    || credential.TargetName == null || !target.equals(credential.TargetName.getWideString(0))
                    || credential.UserName == null
                    || !target.substring(target.lastIndexOf('/') + 1).equals(credential.UserName.getWideString(0)))
                throw new IllegalStateException("Local ADMIN credential has an invalid format");
            byte[] bytes = credential.CredentialBlob.getByteArray(0, credential.CredentialBlobSize);
            try {
                char[] secret = new char[bytes.length];
                for (int i = 0; i < bytes.length; i++) {
                    int value = Byte.toUnsignedInt(bytes[i]);
                    secret[i] = (char) value;
                    char c = secret[i];
                    if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                        Arrays.fill(secret, '\0');
                        throw new IllegalStateException("Local ADMIN credential has an invalid format");
                    }
                }
                return secret;
            } finally {
                Arrays.fill(bytes, (byte) 0);
                credential.CredentialBlob.setMemory(0L,
                        Integer.toUnsignedLong(credential.CredentialBlobSize), (byte) 0);
            }
        } finally {
            Advapi32.INSTANCE.CredFree(credentialPointer);
        }
    }
}
