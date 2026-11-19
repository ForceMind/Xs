package dev.forcemind.xs.tests;
import dev.forcemind.xs.core.ArchiveSearch;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
/** Export evaluated safe MATCH terms, not a second implementation of tokenization. */
public final class FtsCompatibilityDump {
    private static void emit(String name,ArchiveSearch.Query q){
        for(String arg:q.args)if(arg.startsWith("native*"))
            System.out.println(name+":"+Base64.getEncoder().encodeToString(arg.getBytes(StandardCharsets.UTF_8)));
    }
    public static void main(String[] args){
        ArchiveSearch.Filter f=new ArchiveSearch.Filter();f.keyword="native resource";
        emit("current",ArchiveSearch.build("local:fts",f));
        f.includeSnapshotVersions=true;emit("history",ArchiveSearch.build("local:fts",f));
        emit("versions",ArchiveSearch.buildSnapshots("local:fts","42","native\tresource",50,0));
        f.keyword="native OR resource";emit("literalOperator",ArchiveSearch.build("local:fts",f));
    }
}
