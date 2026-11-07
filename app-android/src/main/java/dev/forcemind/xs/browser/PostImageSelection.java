package dev.forcemind.xs.browser;

import dev.forcemind.xs.core.MediaDownloadPolicy;
import dev.forcemind.xs.core.PostUrls;
import dev.forcemind.xs.core.StrictJson;
import java.util.ArrayList;
import java.util.List;

/** Explicit native download action only. No body, credentials or network interception. */
final class PostImageSelection {
    private PostImageSelection() {}
    static String script(String postUrl) {
        String id=PostUrls.extractPostId(postUrl);
        if (id==null) throw new IllegalArgumentException("Post required");
        // IDs are digits only; no untrusted JavaScript is interpolated here.
        return "(()=>{if(window.top!==window||document.visibilityState!=='visible'||"+
            "document.querySelector('[role=dialog],[aria-modal=true]'))return JSON.stringify([]);"+
            "const id='"+id+"',result=[];"+
            "const postId=u=>{try{return new URL(u,location.href).pathname.match(/\\/status\\/(\\d+)(?:\\/|$)/)?.[1]}catch(e){return null}};"+
            "if(postId(location.href)!==id)return JSON.stringify([]);"+
            "for(const card of Array.from(document.querySelectorAll('article[data-testid=tweet]')).slice(0,64)){"+
            "const time=card.querySelector('time'),anchor=time&&time.closest('a');"+
            "if(!anchor||postId(anchor.href)!==id)continue;"+
            "for(const image of Array.from(card.querySelectorAll('[data-testid=tweetPhoto] img')).slice(0,10)){"+
            "const link=image.closest('a');if(image.closest('article')!==card||!link||postId(link.href)!==id)continue;"+
            "const rect=image.getBoundingClientRect();if(!rect.width||!rect.height)continue;"+
            "const url=image.currentSrc||image.src;if(url&&url.length<=2048&&!result.includes(url))result.push(url);"+
            "if(result.length===10)break;}if(result.length===10)break;}return JSON.stringify(result)})()";
    }
    static List<String> parse(String postUrl,String raw) {
        if (raw==null||raw.length()>30000) throw new IllegalArgumentException("Invalid image list");
        try {
            Object decoded=StrictJson.parse(raw);
            if (!(decoded instanceof String)) throw new IllegalArgumentException();
            Object array=StrictJson.parse((String)decoded);
            if (!(array instanceof List)) throw new IllegalArgumentException();
            List<?> values=(List<?>)array;
            if (values.size()>10) throw new IllegalArgumentException();
            List<String> result=new ArrayList<>();
            for (int i=0;i<values.size();i++) {
                Object item=values.get(i);
                if (!(item instanceof String)) throw new IllegalArgumentException();
                String url=MediaDownloadPolicy.validateImage(postUrl,(String)item);
                if (!result.contains(url)) result.add(url);
            }
            return result;
        } catch (Exception invalid) { throw new IllegalArgumentException("Invalid image list"); }
    }
}
