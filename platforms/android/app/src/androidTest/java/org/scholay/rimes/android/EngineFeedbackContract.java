package org.scholay.rimes.android;

import android.app.Instrumentation;
import android.provider.Settings;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.scholay.rimes.core.NineKeyPinyin;
import org.scholay.rimes.core.RimeEngine;

/** Actual APK dictionaries and JNI, using only public synthetic words and isolated user data. */
final class EngineFeedbackContract {
    static String run(Instrumentation instrumentation) throws Exception {
        android.content.Context context=instrumentation.getTargetContext();
        String selected=Settings.Secure.getString(context.getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD);
        if(selected!=null && selected.startsWith(context.getPackageName()+"/"))
            throw new IllegalStateException("Select another IME before the isolated JNI contract");
        File data=EngineResources.prepare(context);
        File user=new File(context.getNoBackupFilesDir(),"engine-feedback-user-"+UUID.randomUUID());
        if(!user.mkdirs()) throw new java.io.IOException("Cannot create isolated feedback directory");
        JSONObject report=EngineWorker.QUEUE.submit(() -> {
            NativeRimeEngine engine=new NativeRimeEngine(); engine.initialize(data.getAbsolutePath(),user.getAbsolutePath());
            JSONObject out=new JSONObject(); JSONArray queries=new JSONArray(),typing=new JSONArray();
            long session=engine.createSession();
            try {
                for(String[] query:new String[][]{{"rimes_pinyin_private","lijie"},{"rimes_pinyin9_private","54543"},{"rimes_ziranma_private","lijx"}}) {
                    if(!engine.selectSchema(session,query[0])) throw new AssertionError("feedback schema "+query[0]);
                    engine.clearComposition(session);
                    RimeEngine.Snapshot state=RimeEngine.Snapshot.EMPTY;
                    for(char key:query[1].toCharArray()) state=engine.processKey(session,key);
                    if(state.candidates.isEmpty() || !"理解".equals(state.candidates.get(0)))
                        throw new AssertionError("common homophone remains first: "+state.candidates);
                    JSONArray first=new JSONArray(state.candidates);
                    int found=-1;
                    for(int page=0;page<10;page++) {
                        int index=state.candidates.indexOf("力竭");
                        if(index>=0) { found=state.pageStart+index; break; }
                        if(state.lastPage) break;
                        state=engine.processKey(session,0xff56);
                    }
                    if(found<0) throw new AssertionError("missing complete reported word in first 90 candidates: "+query[0]);
                    String commit=engine.selectCandidate(session,found).commit;
                    if(!"力竭".equals(commit)) throw new AssertionError("exact reported word commit: "+commit);
                    queries.put(new JSONObject().put("schema",query[0]).put("code",query[1]).put("first_page",first)
                            .put("reported_word_index",found).put("commit",commit));
                    engine.clearComposition(session);
                }
                if(!engine.selectSchema(session,"rimes_pinyin9_private")) throw new AssertionError("nine-key display schema");
                engine.clearComposition(session);
                RimeEngine.Snapshot state=RimeEngine.Snapshot.EMPTY;
                String[] expectedDisplay={"n","ni","ni'h","ni'ha","ni'hao"}; int typed=0;
                for(char key:"64426".toCharArray()) {
                    state=engine.processKey(session,key);
                    String reading=state.comments.isEmpty()?null:state.comments.get(0);
                    String displayed=NineKeyPinyin.displayPreedit(state.raw,state.preedit,reading);
                    if(!expectedDisplay[typed++].equals(displayed))
                        throw new AssertionError("incremental nine-key display: raw="+state.raw+", displayed="+displayed);
                    typing.put(new JSONObject().put("raw",state.raw).put("preedit",state.preedit)
                            .put("reading",reading==null?"":reading)
                            .put("display",displayed));
                }
                if(!"ni'hao".equals(NineKeyPinyin.displayPreedit(state.raw,state.preedit,state.comments.get(0))))
                    throw new AssertionError("readable complete nine-key display");
                if(!"你好".equals(engine.selectCandidate(session,0).commit)) throw new AssertionError("nine-key Chinese commit");
                out.put("queries",queries).put("nine_key_typing",typing).put("private_schemas",true).put("synthetic_user_directory",true);
                return out;
            } finally { engine.destroySession(session); }
        }).get(20,TimeUnit.SECONDS);
        Files.write(new File(context.getFilesDir(),"engine-feedback.json").toPath(),(report.toString(2)+"\n").getBytes(StandardCharsets.UTF_8));
        return "PASS actual JNI feedback words, rankings, nine-key display; report files/engine-feedback.json\n"+report.toString(2)+"\n";
    }
}
