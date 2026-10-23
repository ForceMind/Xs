package dev.forcemind.xs.tests;

import dev.forcemind.xs.core.ArchiveSchema;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Exports evaluated Java DDL strings for SQLite integration tests. */
public final class SchemaDump {
    public static void main(String[] args) {
        for (String ddl : ArchiveSchema.CREATE) {
            System.out.println(Base64.getEncoder().encodeToString(
                ddl.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
