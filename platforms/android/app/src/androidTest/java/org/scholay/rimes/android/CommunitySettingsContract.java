package org.scholay.rimes.android;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Private, recoverable settings isolation for synthetic community regression runs only. */
final class CommunitySettingsContract {
    private static final String OWNER="CommunitySettingsContract-v1";
    private static final String BACKUP="community-settings-backup.json";
    private static final Set<String> PLUGINS=new HashSet<>(Arrays.asList("chord","translate","ask","polish","poem","art"));
    private CommunitySettingsContract() {}

    /** The caller keeps the real keyboard stopped/away until prepare has completed. */
    static synchronized void prepare(Instrumentation instrumentation) throws Exception {
        Context context=instrumentation.getTargetContext().getApplicationContext();
        File file=backupFile(context);
        if(file.exists() || companion(file,".bak").exists() || companion(file,".new").exists())
            throw new IOException("Community settings backup already exists; restore it first");
        SharedPreferences keyboard=context.getSharedPreferences(KeyboardSettings.PREFERENCES_NAME,Context.MODE_PRIVATE);
        SharedPreferences plugins=context.getSharedPreferences(OfficialPluginStore.PREFERENCES,Context.MODE_PRIVATE);
        // Do not construct OfficialPluginStore before this snapshot: its migration can write prefs.
        JSONObject saved=new JSONObject().put("owner",OWNER).put("format",1).put("package",context.getPackageName())
                .put(KeyboardSettings.PREFERENCES_NAME,encode(keyboard.getAll()))
                .put(OfficialPluginStore.PREFERENCES,encode(plugins.getAll()));
        Map<String,String> states=bundledStates(context);
        byte[] bytes=saved.toString().getBytes(StandardCharsets.UTF_8);
        // Exclusively reserve the base file, then commit its complete contents through AtomicFile.
        if(!file.createNewFile()) throw new IOException("Community settings backup reservation failed");
        AtomicFile atomic=new AtomicFile(file); FileOutputStream output=null;
        try {
            output=atomic.startWrite(); output.write(bytes); atomic.finishWrite(output); output=null;
            if(!Arrays.equals(bytes,atomic.readFully())) throw new IOException("Community settings backup readback failed");
        } catch(Exception failure) {
            if(output!=null) atomic.failWrite(output);
            atomic.delete(); // No preferences have been changed; this reservation is ours.
            throw failure;
        }
        if(!keyboard.edit().putString(KeyboardSettings.KEY_SCHEMA,KeyboardSettings.DEFAULT_SCHEMA)
                .putString(KeyboardSettings.KEY_LAYOUT,KeyboardSettings.DEFAULT_LAYOUT)
                .putString(KeyboardSettings.KEY_THEME,KeyboardSettings.DEFAULT_THEME)
                .putString(KeyboardSettings.KEY_TRANSLATION_DIRECTION,KeyboardSettings.DEFAULT_TRANSLATION_DIRECTION)
                .putBoolean(KeyboardSettings.KEY_LEARNING,false).putBoolean(KeyboardSettings.KEY_AI_MOCK_ENABLED,true)
                .remove(OpenAiSettings.KEY).commit()) throw new IOException("Community keyboard settings commit failed; backup retained");
        SharedPreferences.Editor edit=plugins.edit().putBoolean("migration",true).putBoolean("legacy",false);
        for(Map.Entry<String,String> state:states.entrySet()) edit.putString(state.getKey(),state.getValue());
        if(!edit.commit()) throw new IOException("Community plugin settings commit failed; backup retained");
        if(keyboard.contains(OpenAiSettings.KEY) || keyboard.getBoolean(KeyboardSettings.KEY_LEARNING,true))
            throw new IOException("Community local-only settings verification failed; backup retained");
        OfficialPluginStore store=new OfficialPluginStore(context);
        for(String id:PLUGINS) if(!store.enabled(id)) throw new IOException("Community bundled plugin verification failed; backup retained");
    }

    /** The caller must stop/leave the keyboard before restore and restart it afterwards. */
    static synchronized void restore(Instrumentation instrumentation) throws Exception {
        Context context=instrumentation.getTargetContext().getApplicationContext();
        File file=backupFile(context); AtomicFile atomic=new AtomicFile(file);
        if(!file.exists() && !companion(file,".bak").exists()) throw new IOException("Community settings backup is missing");
        JSONTokener token=new JSONTokener(new String(atomic.readFully(),StandardCharsets.UTF_8));
        Object value=token.nextValue();
        if(!(value instanceof JSONObject) || token.nextClean()!=0) throw new IOException("Community settings backup is invalid");
        JSONObject saved=(JSONObject)value;
        if(!OWNER.equals(saved.getString("owner")) || saved.getInt("format")!=1 || !context.getPackageName().equals(saved.getString("package")))
            throw new IOException("Community settings backup belongs to a different owner");
        // Validate both complete snapshots before writing either preference file.
        Map<String,Object> keyboard=decode(saved.getJSONObject(KeyboardSettings.PREFERENCES_NAME));
        Map<String,Object> plugins=decode(saved.getJSONObject(OfficialPluginStore.PREFERENCES));
        SharedPreferences keyboardPrefs=context.getSharedPreferences(KeyboardSettings.PREFERENCES_NAME,Context.MODE_PRIVATE);
        SharedPreferences pluginPrefs=context.getSharedPreferences(OfficialPluginStore.PREFERENCES,Context.MODE_PRIVATE);
        replace(keyboardPrefs,keyboard); replace(pluginPrefs,plugins);
        if(!keyboard.equals(keyboardPrefs.getAll()) || !plugins.equals(pluginPrefs.getAll()))
            throw new IOException("Community settings restoration verification failed; backup retained");
        atomic.delete();
        if(file.exists() || companion(file,".bak").exists() || companion(file,".new").exists())
            throw new IOException("Community settings restored but owned backup could not be removed");
    }
    private static File backupFile(Context context) throws IOException {
        File directory=context.getNoBackupFilesDir();
        if(!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Community settings backup directory unavailable");
        return new File(directory,BACKUP);
    }
    private static File companion(File file,String suffix) { return new File(file.getPath()+suffix); }
    private static JSONObject encode(Map<String,?> values) throws Exception {
        JSONObject result=new JSONObject();
        for(Map.Entry<String,?> entry:values.entrySet()) {
            Object value=entry.getValue(); JSONObject typed=new JSONObject();
            if(value instanceof String) typed.put("type","string").put("value",value);
            else if(value instanceof Boolean) typed.put("type","boolean").put("value",value);
            else if(value instanceof Integer) typed.put("type","int").put("value",value);
            else if(value instanceof Long) typed.put("type","long").put("value",value.toString());
            else if(value instanceof Float) typed.put("type","float").put("value",Float.floatToRawIntBits((Float)value));
            else if(value instanceof Set<?>) {
                TreeSet<String> strings=new TreeSet<>();
                for(Object item:(Set<?>)value) {
                    if(!(item instanceof String)) throw new IOException("Unsupported community preference set type");
                    strings.add((String)item);
                }
                typed.put("type","stringSet").put("value",new JSONArray(strings));
            } else throw new IOException("Unsupported community preference type");
            result.put(entry.getKey(),typed);
        }
        return result;
    }
    private static Map<String,Object> decode(JSONObject values) throws Exception {
        Map<String,Object> result=new LinkedHashMap<>();
        java.util.Iterator<String> keys=values.keys();
        while(keys.hasNext()) {
            String key=keys.next(); JSONObject typed=values.getJSONObject(key); Object value;
            switch(typed.getString("type")) {
                case "string": value=typed.getString("value"); break;
                case "boolean": value=typed.getBoolean("value"); break;
                case "int": value=typed.getInt("value"); break;
                case "long": value=Long.parseLong(typed.getString("value")); break;
                case "float": value=Float.intBitsToFloat(typed.getInt("value")); break;
                case "stringSet":
                    JSONArray array=typed.getJSONArray("value"); Set<String> strings=new HashSet<>();
                    for(int i=0;i<array.length();i++) strings.add(array.getString(i)); value=strings; break;
                default: throw new IOException("Unsupported community backup type");
            }
            result.put(key,value);
        }
        return result;
    }
    private static void replace(SharedPreferences preferences,Map<String,Object> values) throws IOException {
        SharedPreferences.Editor edit=preferences.edit().clear();
        for(Map.Entry<String,Object> entry:values.entrySet()) {
            String key=entry.getKey(); Object value=entry.getValue();
            if(value instanceof String) edit.putString(key,(String)value);
            else if(value instanceof Boolean) edit.putBoolean(key,(Boolean)value);
            else if(value instanceof Integer) edit.putInt(key,(Integer)value);
            else if(value instanceof Long) edit.putLong(key,(Long)value);
            else if(value instanceof Float) edit.putFloat(key,(Float)value);
            else if(value instanceof Set<?>) {
                Set<String> strings=new HashSet<>(); for(Object item:(Set<?>)value) strings.add((String)item);
                edit.putStringSet(key,strings);
            } else throw new IOException("Unsupported community restoration type");
        }
        if(!edit.commit()) throw new IOException("Community settings restoration commit failed; backup retained");
    }
    private static Map<String,String> bundledStates(Context context) throws Exception {
        JSONObject catalog=new JSONObject(new String(asset(context,"official-plugins/catalog.json",1024*1024),StandardCharsets.UTF_8));
        if(catalog.getInt("schemaVersion")!=1) throw new IOException("Community plugin catalog is invalid");
        Map<String,String> states=new LinkedHashMap<>(); Set<String> found=new HashSet<>(); JSONArray entries=catalog.getJSONArray("plugins");
        for(int i=0;i<entries.length();i++) {
            OfficialPluginStore.Entry entry=new OfficialPluginStore.Entry(entries.getJSONObject(i));
            if(!PLUGINS.contains(entry.legacyID)) continue;
            if(!found.add(entry.legacyID)) throw new IOException("Community plugin catalog contains duplicates");
            byte[] bytes=asset(context,"official-plugins/"+entry.asset,OfficialPluginStore.LIMIT);
            if(!entry.hash.equals(hex(MessageDigest.getInstance("SHA-256").digest(bytes))))
                throw new IOException("Community bundled package hash failed");
            JSONObject data=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
            if(!entry.id.equals(data.getString("id")) || !entry.version.equals(data.getString("version")))
                throw new IOException("Community bundled package identity failed");
            JSONObject state=new JSONObject().put("grant","community-"+UUID.randomUUID()).put("installed",true)
                    .put("enabled",true).put("bundled",true).put("sha256",entry.hash);
            states.put("state."+entry.id,state.toString());
        }
        if(!found.equals(PLUGINS)) throw new IOException("Community bundled package subset is incomplete");
        return states;
    }
    private static byte[] asset(Context context,String name,int limit) throws IOException {
        try(InputStream input=context.getAssets().open(name);ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            byte[] block=new byte[8192]; int length;
            while((length=input.read(block))!=-1) {
                if(output.size()+length>limit) throw new IOException("Community bundled asset exceeds its size limit");
                output.write(block,0,length);
            }
            return output.toByteArray();
        }
    }
    private static String hex(byte[] bytes) {
        StringBuilder result=new StringBuilder(bytes.length*2);
        for(byte value:bytes) result.append(Character.forDigit((value>>>4)&15,16)).append(Character.forDigit(value&15,16));
        return result.toString();
    }
}
