package cn.course.antifraud;

import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.json.JsonMapper;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.security.*;
import java.sql.*;
import java.sql.Timestamp;
import java.math.*;
import java.time.*;
import java.util.*;

@Component
public class Support {
    public final JdbcTemplate db;
    public final JsonMapper json;
    private final byte[] key;
    private org.springframework.transaction.support.TransactionTemplate transactions;
    @org.springframework.beans.factory.annotation.Autowired
    public void transactions(org.springframework.transaction.PlatformTransactionManager manager){transactions=new org.springframework.transaction.support.TransactionTemplate(manager);}

    public String fingerprint(String kind,String normalized){
        try{
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
            byte[] derived=mac.doFinal("antifraud/fingerprint-key/v1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            mac.init(new SecretKeySpec(derived,"HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((kind+"\0"+normalized).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }catch(GeneralSecurityException e){throw new IllegalStateException("线索指纹生成失败",e);}
    }
    public Support(JdbcTemplate db,JsonMapper json,@Value("${app.key}") String key){this.db=db;this.json=json;this.key=Base64.getDecoder().decode(key);if(this.key.length!=32)throw new IllegalStateException("APP_KEY需要32字节");}
    public LocalDateTime now(){return LocalDateTime.now(ZoneId.of("Asia/Shanghai")).withNano(0);}
    public static void require(boolean condition,String message){if(!condition)throw new Problem(400,message);}
    public static void conflict(boolean condition,String message){if(!condition)throw new Problem(409,message);}
    public static String str(Map<String,?> m,String k){Object v=m.get(k);return v==null?"":v.toString().trim();}
    public static long num(Map<String,?> m,String k){Object v=m.get(k);return v==null||v.toString().isBlank()?0:Long.parseLong(v.toString());}
    public static boolean yes(Map<String,?> m,String k){Object v=m.get(k);return v!=null&&(v.equals(true)||v.toString().equals("1")||v.toString().equalsIgnoreCase("true"));}
    public static String required(Map<String,?> m,String k,int max){String v=str(m,k);require(!v.isBlank()&&v.length()<=max,"请填写有效的"+k+"（最多"+max+"字）");return v;}
    public static BigDecimal money(Object v){try{BigDecimal x=new BigDecimal(v==null?"0":v.toString());require(x.scale()<=2&&x.abs().compareTo(new BigDecimal("999999999999.99"))<=0,"金额最多两位小数且不能超出范围");return x.setScale(2);}catch(NumberFormatException e){throw new Problem(400,"金额格式不正确");}}
    public static BigDecimal amount(Map<String,?> m,String k){return money(m.get(k));}
    public static LocalDateTime time(Object value){try{return LocalDateTime.parse(value.toString().replace(' ','T')).withNano(0);}catch(Exception e){throw new Problem(400,"时间格式不正确");}}
    public String encode(Object o){return json.writeValueAsString(o);}
    @SuppressWarnings("unchecked") public Map<String,Object> decode(String s){return s==null||s.isBlank()?new LinkedHashMap<>():json.readValue(s,LinkedHashMap.class);}
    public List<Map<String,Object>> rows(String sql,Object...args){return db.query(sql,(rs,n)->{Map<String,Object> m=new LinkedHashMap<>();var meta=rs.getMetaData();for(int i=1;i<=meta.getColumnCount();i++){Object v=rs.getObject(i);if(v instanceof Timestamp t)v=t.toLocalDateTime().toString();m.put(meta.getColumnLabel(i),v);}return m;},args);}
    public Map<String,Object> optional(String sql,Object...args){var r=rows(sql,args);return r.isEmpty()?null:r.getFirst();}
    public Map<String,Object> one(String sql,Object...args){var r=optional(sql,args);if(r==null)throw new Problem(404,"记录不存在或无权访问");return r;}
    public long count(String sql,Object...args){Long v=db.queryForObject(sql,Long.class,args);return v==null?0:v;}
    public long insert(String sql,Object...args){GeneratedKeyHolder h=new GeneratedKeyHolder();db.update(c->{PreparedStatement p=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);return p;},h);return Objects.requireNonNull(h.getKey()).longValue();}
    public String code(String prefix){return prefix+now().format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd"))+UUID.randomUUID().toString().replace("-","").substring(0,10).toUpperCase();}
    public static String random(){byte[] b=new byte[32];new SecureRandom().nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    public static String hash(String input){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    public String encrypt(String input){if(input==null)return null;try{byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));byte[] out=c.doFinal(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));return Base64.getEncoder().encodeToString(iv)+":"+Base64.getEncoder().encodeToString(out);}catch(Exception e){throw new IllegalStateException("加密失败",e);}}
    public String decrypt(Object input){if(input==null)return "";try{String[] v=input.toString().split(":");Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Base64.getDecoder().decode(v[0])));return new String(c.doFinal(Base64.getDecoder().decode(v[1])),java.nio.charset.StandardCharsets.UTF_8);}catch(Exception e){throw new IllegalStateException("密钥与加密数据不匹配",e);}}
    public static String password(String raw){String salt=random();return "pbkdf2$210000$"+salt+"$"+derive(raw,salt,210000);}
    private static String derive(String raw,String salt,int rounds){try{return Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(raw.toCharArray(),Base64.getUrlDecoder().decode(salt),rounds,256)).getEncoded());}catch(Exception e){throw new IllegalStateException(e);}}
    public static boolean checkPassword(String raw,String stored){try{String[] p=stored.split("\\$");return MessageDigest.isEqual(derive(raw,p[2],Integer.parseInt(p[1])).getBytes(),p[3].getBytes());}catch(Exception e){return false;}}
    public void log(User u,String action,String type,long id,String oldState,String newState,String note){
        transactions.executeWithoutResult(status->{
        var head=one("SELECT * FROM af_audit_head WHERE id=1 FOR UPDATE");
        long logId=insert("INSERT INTO af_log(actor_id,actor_role,action,entity_type,entity_id,old_state,new_state,note,created_at) VALUES(?,?,?,?,?,?,?,?,?)",u==null?null:u.id(),u==null?"SYSTEM":u.role(),action,type,id,oldState,newState,note,now());
        String previous=str(head,"last_hash"),digest=Audit.digest(this,one("SELECT * FROM af_log WHERE id=?",logId),previous);
        db.update("UPDATE af_log SET prev_hash=?,entry_hash=? WHERE id=?",previous,digest,logId);
        db.update("UPDATE af_audit_head SET last_id=?,last_hash=?,entry_count=entry_count+1 WHERE id=1",logId,digest);
        });
    }

    public static String mask(String v){if(v==null||v.length()<5)return "***";return v.substring(0,2)+"****"+v.substring(v.length()-4);}
    public static Map<String,Object> map(Object...pairs){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put(pairs[i].toString(),pairs[i+1]);return m;}
    public record User(long id,String username,String displayName,String role,long orgId,long communityId,long victimId,boolean verified,boolean admin){
        public void roles(String...roles){if(!Arrays.asList(roles).contains(role))throw new Problem(403,"当前账号无此操作权限");}
        public void administrator(){roles("CENTER");if(!admin)throw new Problem(403,"需要中心管理权限");}
    }
    public static class Problem extends RuntimeException {public final int status;public Problem(int status,String message){super(message);this.status=status;}}
}
