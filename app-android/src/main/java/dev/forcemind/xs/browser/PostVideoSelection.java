package dev.forcemind.xs.browser;

import dev.forcemind.xs.core.MediaDownloadPolicy;
import dev.forcemind.xs.core.PostUrls;
import dev.forcemind.xs.core.StrictJson;
import java.util.ArrayList;
import java.util.List;

/** Explicit, current-detail-only direct-file selection. No stream interception or credential export. */
final class PostVideoSelection {
    private PostVideoSelection() {}
    static String script(String postUrl) {
        String id=PostUrls.extractPostId(postUrl);if(id==null)throw new IllegalArgumentException("Post required");
        return "(()=>{if(window.top!==window||document.visibilityState!=='visible'||"+
            "document.querySelector('[role=dialog],[aria-modal=true]'))return JSON.stringify([]);"+
            "const id='"+id+"',result=[];"+
            "const postId=u=>{try{return new URL(u,location.href).pathname.match(/\\/status\\/(\\d+)(?:\\/|$)/)?.[1]}catch(e){return null}};"+
            "if(postId(location.href)!==id)return JSON.stringify([]);"+
            "for(const card of Array.from(document.querySelectorAll('article[data-testid=tweet]')).slice(0,64)){"+
            "const time=card.querySelector('time'),anchor=time&&time.closest('a');if(!anchor||postId(anchor.href)!==id)continue;"+
            "for(const video of Array.from(card.querySelectorAll('video')).slice(0,4)){"+
            "if(video.closest('article')!==card||video.closest('[data-testid=quoteTweet],[data-testid=quoteTweetContainer],[data-protected],[data-drm],[hidden],[aria-hidden=true]'))continue;"+
            "const link=video.closest('a[href]'),role=video.closest('[role=link]');"+
            "if(link&&postId(link.href)!==id)continue;"+
            "if(role){const status=role.querySelector('a[href*=\"/status/\"]');if(!status||postId(status.href)!==id)continue;}"+
            "if(!video.closest('[data-testid=videoPlayer]')&&!link)continue;"+
            "if(video.mediaKeys||video.disablePictureInPicture||video.hasAttribute('disablepictureinpicture')||(video.getAttribute('controlslist')||'').toLowerCase().includes('nodownload'))continue;"+
            "const style=getComputedStyle(video);if(style.visibility!=='visible'||Number(style.opacity)===0)continue;"+
            "const rect=video.getBoundingClientRect();if(rect.width<40||rect.height<40||rect.bottom<=0||rect.top>=innerHeight)continue;"+
            "const source=video.querySelector('source[type=\"video/mp4\"]');const url=video.currentSrc||video.src||(source&&source.src);"+
            "if(url&&url.length<=2048&&!result.includes(url))result.push(url);if(result.length===4)break;}if(result.length===4)break;}return JSON.stringify(result)})()";
    }
    static List<String> parse(String postUrl,String raw) {
        if(raw==null||raw.length()>16000)throw new IllegalArgumentException("Invalid video list");
        try {
            Object outer=StrictJson.parse(raw);if(!(outer instanceof String))throw new IllegalArgumentException();
            Object inner=StrictJson.parse((String)outer);if(!(inner instanceof List))throw new IllegalArgumentException();
            List<?> values=(List<?>)inner;if(values.size()>4)throw new IllegalArgumentException();
            List<String> result=new ArrayList<>();
            for(Object value:values){if(!(value instanceof String))throw new IllegalArgumentException();String url=MediaDownloadPolicy.validateVideo(postUrl,(String)value);if(!result.contains(url))result.add(url);}
            return result;
        }catch(Exception invalid){throw new IllegalArgumentException("Unsupported video resource");}
    }
}
