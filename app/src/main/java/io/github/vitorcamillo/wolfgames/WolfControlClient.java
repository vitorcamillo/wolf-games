package io.github.vitorcamillo.wolfgames;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.UUID;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/** HTTPS client for an owner-provisioned, certificate-pinned, LAN-only session controller. */
final class WolfControlClient implements WolfControl {
    private static final int MAX_BYTES=16384;
    private final List<String> bases;
    private volatile int current;
    private final String hostUuid,token;
    private final SSLSocketFactory ssl;
    static volatile String lastGoodHost;
    static final class ControlException extends Exception {
        final String code;
        ControlException(String code) { super(code);this.code=code; }
    }
    // Package-private so the JVM tests can point the client at a local fixture port.
    WolfControlClient(String url,String host,String token,SSLSocketFactory ssl) {
        String base=url.endsWith("/")?url.substring(0,url.length()-1):url;
        this.bases=new ArrayList<>(Collections.singletonList(base));this.current=0;hostUuid=host;this.token=token;this.ssl=ssl;
    }
    WolfControlClient(List<String> bases,String host,String token,SSLSocketFactory ssl) {
        this.bases=new ArrayList<>(bases);this.current=0;hostUuid=host;this.token=token;this.ssl=ssl;
    }
    static WolfControlClient load(File file) throws ControlException {
        return load(file,Collections.emptyList());
    }
    static WolfControlClient load(File file,List<String> discovered) throws ControlException {
        if(!file.exists())return null;
        try {
            if(!file.isFile() || file.length()>MAX_BYTES)throw new ControlException("config_error");
            JSONObject json;
            try(InputStream input=new FileInputStream(file)) {json=new JSONObject(read(input));}
            if(json.getInt("version")!=1)throw new ControlException("config_error");
            String host=json.getString("host_uuid"),token=json.getString("token");
            SSLSocketFactory ssl=trust(json.getString("server_certificate"));
            String urlHost=null;
            if(json.has("url") && !json.isNull("url")) {
                String address=json.getString("url");
                URL url=new URL(address);
                if(!"https".equals(url.getProtocol()) || url.getPort()!=47991 || url.getUserInfo()!=null || url.getQuery()!=null || url.getRef()!=null
                        || !(url.getPath().isEmpty() || "/".equals(url.getPath())) || !privateAddress(url.getHost()))throw new ControlException("config_error");
                urlHost=url.getHost();
            }
            if(!host.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}") || !token.matches("[A-Za-z0-9_-]{32,128}"))throw new ControlException("config_error");
            LinkedHashSet<String> candidates=new LinkedHashSet<>();
            String last=lastGoodHost;
            if(last!=null && !last.isEmpty() && candidates.size()<6)candidates.add(last);
            if(discovered!=null)for(String item:discovered) {
                if(candidates.size()>=6)break;
                if(item!=null && privateAddress(item))candidates.add(item);
            }
            if(urlHost!=null && candidates.size()<6)candidates.add(urlHost);
            // No address yet is valid: provisioning validates before any discovery; requests then fail with network_error.
            List<String> bases=new ArrayList<>();
            for(String candidate:candidates)bases.add("https://"+candidate+":47991");
            return new WolfControlClient(bases,UUID.fromString(host).toString(),token,ssl);
        } catch(ControlException error) {throw error;}
        catch(Exception error) {throw new ControlException("config_error");}
    }
    static SSLSocketFactory trust(String certificate) throws Exception {
        if(certificate.length()>12000)throw new ControlException("config_error");
        ByteArrayInputStream encoded=new ByteArrayInputStream(Base64.getDecoder().decode(certificate));
        X509Certificate cert=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(encoded);
        if(encoded.available()!=0)throw new ControlException("config_error");
        cert.checkValidity();
        KeyStore store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null,null);store.setCertificateEntry("wolf-session-control",cert);
        TrustManagerFactory trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(store);
        SSLContext tls=SSLContext.getInstance("TLS");tls.init(null,trust.getTrustManagers(),null);
        return tls.getSocketFactory();
    }
    private static boolean privateAddress(String host) {
        if(host==null || !host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))return false;
        String[] pieces=host.split("\\.");int[] octets=new int[4];
        for(int i=0;i<4;i++){octets[i]=Integer.parseInt(pieces[i]);if(octets[i]>255 || !pieces[i].equals(String.valueOf(octets[i])))return false;}
        return octets[0]==10 || octets[0]==192&&octets[1]==168 || octets[0]==172&&octets[1]>=16&&octets[1]<=31;
    }
    boolean controls(String host) {return hostUuid.equalsIgnoreCase(host);}
    public WolfSession getSession() throws ControlException {
        ControlException last=null;
        int start=current;
        for(int i=0;i<bases.size();i++) {
            int index=(start+i)%bases.size();
            String base=bases.get(index);
            try {
                WolfSession session=requestOnce("GET","/v1/session",null,base);
                current=index;String resolved=hostOf(base);if(resolved!=null)lastGoodHost=resolved;
                return session;
            } catch(ControlException error) {if(!retriable(error))throw error;last=error;}
        }
        throw last!=null?last:new ControlException("network_error");
    }
    public WolfSession stop(WolfSession expected) throws ControlException {
        if(expected==null || !expected.active)throw new ControlException("invalid_request");
        try {
            JSONObject body=new JSONObject().put("expected_app_id",expected.appId).put("expected_revision",expected.revision);
            WolfSession result=request("POST","/v1/session/stop",body);
            if(result.active)throw new ControlException("protocol_error");
            return result;
        } catch(ControlException error) {throw error;}
        catch(Exception error) {throw new ControlException("protocol_error");}
    }
    String hostUuid() {return hostUuid;}
    private static boolean retriable(ControlException error) {
        return "network_error".equals(error.code) || "tls_error".equals(error.code);
    }
    private static String hostOf(String base) {
        try {return new URL(base).getHost();} catch(Exception ignored) {return null;}
    }
    private HttpsURLConnection open(String method,String path) throws java.io.IOException {
        return open(method,path,bases.get(current));
    }
    private HttpsURLConnection open(String method,String path,String base) throws java.io.IOException {
        HttpsURLConnection connection=(HttpsURLConnection)new URL(base+path).openConnection();
        connection.setSSLSocketFactory(ssl);
        // The address comes from mDNS discovery, so the name is not checked: trust is the pinned certificate
        // (the trust store holds only the provisioned one) plus the host_uuid checked on every response.
        connection.setHostnameVerifier((hostname,session)->true);
        connection.setConnectTimeout(2000);connection.setReadTimeout(8000);connection.setUseCaches(false);connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(method);connection.setRequestProperty("Authorization","Bearer "+token);connection.setRequestProperty("Accept","application/json");
        return connection;
    }
    private static void checkStatus(HttpsURLConnection connection,int status) throws Exception {
        String type=connection.getContentType();
        if(type==null || !"application/json".equalsIgnoreCase(type.split(";",2)[0].trim()))throw new ControlException("protocol_error");
        JSONObject json;
        try(InputStream input=connection.getErrorStream()){json=new JSONObject(read(input));}
        if(status==401)throw new ControlException("unauthorized");
        String code=json.optJSONObject("error")==null?"":json.getJSONObject("error").optString("code","");
        if(status==409 && (code.equals("session_changed") || code.equals("busy")))throw new ControlException(code);
        if(status==504)throw new ControlException("stop_timeout");
        if(status==502 || status==503 || status==500)throw new ControlException("upstream_unavailable");
        throw new ControlException("protocol_error");
    }
    List<WolfCatalogSync.Entry> games() throws ControlException {
        ControlException last=null;
        int start=current;
        for(int i=0;i<bases.size();i++) {
            int index=(start+i)%bases.size();
            String base=bases.get(index);
            try {
                List<WolfCatalogSync.Entry> result=gamesOnce(base);
                current=index;String resolved=hostOf(base);if(resolved!=null)lastGoodHost=resolved;
                return result;
            } catch(ControlException error) {if(!retriable(error))throw error;last=error;}
        }
        throw last!=null?last:new ControlException("network_error");
    }
    private List<WolfCatalogSync.Entry> gamesOnce(String base) throws ControlException {
        HttpsURLConnection connection=null;
        try {
            connection=open("GET","/v1/games",base);int status=connection.getResponseCode();
            if(status!=200)checkStatus(connection,status);
            String type=connection.getContentType();
            if(type==null || !"application/json".equalsIgnoreCase(type.split(";",2)[0].trim()))throw new ControlException("protocol_error");
            JSONObject json;
            try(InputStream input=connection.getInputStream()){json=new JSONObject(new String(readBytes(input,65536),StandardCharsets.UTF_8));}
            if(!hostUuid.equals(json.getString("host_uuid")))throw new ControlException("protocol_error");
            JSONArray games=json.getJSONArray("games");if(games.length()>64)throw new ControlException("protocol_error");
            List<WolfCatalogSync.Entry> result=new ArrayList<>();
            for(int i=0;i<games.length();i++) {
                JSONObject game=games.getJSONObject(i);
                if(!(game.get("app_id") instanceof String) || !(game.get("name") instanceof String) || !game.has("cover"))throw new ControlException("protocol_error");
                String id=game.getString("app_id"),name=MoonlightCatalog.label(game.getString("name"));WolfSession.validateId(id);
                String cover=null;
                if(!game.isNull("cover")) {
                    if(!(game.get("cover") instanceof String))throw new ControlException("protocol_error");
                    cover=game.getString("cover");if(!cover.equals(id+".png") && !cover.equals(id+".jpg"))throw new ControlException("protocol_error");
                }
                result.add(new WolfCatalogSync.Entry(id,name,cover));
            }
            return result;
        } catch(ControlException error) {throw error;}
        catch(SSLException error) {throw new ControlException("tls_error");}
        catch(java.io.IOException error) {throw new ControlException("network_error");}
        catch(Exception error) {throw new ControlException("protocol_error");}
        finally {if(connection!=null)connection.disconnect();}
    }
    byte[] cover(String appId) throws ControlException {
        ControlException last=null;
        int start=current;
        for(int i=0;i<bases.size();i++) {
            int index=(start+i)%bases.size();
            String base=bases.get(index);
            try {
                byte[] result=coverOnce(appId,base);
                current=index;String resolved=hostOf(base);if(resolved!=null)lastGoodHost=resolved;
                return result;
            } catch(ControlException error) {if(!retriable(error))throw error;last=error;}
        }
        throw last!=null?last:new ControlException("network_error");
    }
    private byte[] coverOnce(String appId,String base) throws ControlException {
        HttpsURLConnection connection=null;
        try {
            WolfSession.validateId(appId);connection=open("GET","/v1/games/"+appId+"/cover",base);int status=connection.getResponseCode();
            if(status==404)throw new ControlException("not_found");
            if(status!=200)checkStatus(connection,status);
            String type=connection.getContentType();if(type==null)throw new ControlException("protocol_error");
            type=type.split(";",2)[0].trim();
            if(!"image/png".equalsIgnoreCase(type) && !"image/jpeg".equalsIgnoreCase(type))throw new ControlException("protocol_error");
            try(InputStream input=connection.getInputStream()){return readBytes(input,4*1024*1024);}
        } catch(ControlException error) {throw error;}
        catch(SSLException error) {throw new ControlException("tls_error");}
        catch(java.io.IOException error) {throw new ControlException("network_error");}
        catch(Exception error) {throw new ControlException("protocol_error");}
        finally {if(connection!=null)connection.disconnect();}
    }
    private static byte[] readBytes(InputStream input,int limit) throws java.io.IOException,ControlException {
        if(input==null)throw new ControlException("protocol_error");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[2048];int count;
        while((count=input.read(buffer))!=-1){if(bytes.size()+count>limit)throw new ControlException("protocol_error");bytes.write(buffer,0,count);}
        return bytes.toByteArray();
    }
    private WolfSession request(String method,String path,JSONObject body) throws ControlException {
        return requestOnce(method,path,body,bases.get(current));
    }
    private WolfSession requestOnce(String method,String path,JSONObject body,String base) throws ControlException {
        HttpsURLConnection connection=null;
        try {
            connection=open(method,path,base);
            if(body!=null) {
                byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type","application/json");connection.setFixedLengthStreamingMode(bytes.length);
                try(OutputStream out=connection.getOutputStream()){out.write(bytes);}
            }
            int status=connection.getResponseCode();String type=connection.getContentType();
            if(type==null || !"application/json".equalsIgnoreCase(type.split(";",2)[0].trim()))throw new ControlException("protocol_error");
            JSONObject json;
            try(InputStream input=status>=400?connection.getErrorStream():connection.getInputStream()){json=new JSONObject(read(input));}
            if(status!=200) {
                if(status==401)throw new ControlException("unauthorized");
                String code=json.optJSONObject("error")==null?"":json.getJSONObject("error").optString("code","");
                if(status==409 && (code.equals("session_changed") || code.equals("busy")))throw new ControlException(code);
                if(status==504)throw new ControlException("stop_timeout");
                if(status==502 || status==503 || status==500)throw new ControlException("upstream_unavailable");
                throw new ControlException("protocol_error");
            }
            if(!hostUuid.equals(json.getString("host_uuid")))throw new ControlException("protocol_error");
            for(String key:new String[]{"status","app_id","app_name","revision"})if(!json.has(key))throw new ControlException("protocol_error");
            String state=json.getString("status");
            if(state.equals("inactive")) {
                if(!json.isNull("app_id") || !json.isNull("app_name") || !json.isNull("revision"))throw new ControlException("protocol_error");
                return WolfSession.inactive();
            }
            if(!state.equals("active") || !(json.get("app_id") instanceof String) || !(json.get("app_name") instanceof String) || !(json.get("revision") instanceof String))throw new ControlException("protocol_error");
            return new WolfSession(true,json.getString("app_id"),json.getString("app_name"),json.getString("revision"));
        } catch(ControlException error) {throw error;}
        catch(SSLException error) {throw new ControlException("tls_error");}
        catch(java.io.IOException error) {throw new ControlException("network_error");}
        catch(Exception error) {throw new ControlException("protocol_error");}
        finally {if(connection!=null)connection.disconnect();}
    }
    private static String read(InputStream input) throws java.io.IOException,ControlException {
        if(input==null)throw new ControlException("protocol_error");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[2048];int count;
        while((count=input.read(buffer))!=-1){if(bytes.size()+count>MAX_BYTES)throw new ControlException("protocol_error");bytes.write(buffer,0,count);}
        return new String(bytes.toByteArray(),StandardCharsets.UTF_8);
    }
}
