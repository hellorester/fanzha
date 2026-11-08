package cn.course.antifraud;

import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static cn.course.antifraud.Support.*;

@Component
public class Auth implements HandlerInterceptor, WebMvcConfigurer {
    private final Support s;
    private final Map<String,List<Long>> failures=new ConcurrentHashMap<>();
    public Auth(Support s){this.s=s;}
    public void addInterceptors(InterceptorRegistry r){r.addInterceptor(this).addPathPatterns("/**");}
    public String token(HttpSession session){if(session.getAttribute("csrf")==null)session.setAttribute("csrf",random());return session.getAttribute("csrf").toString();}
    public User user(HttpServletRequest req){User u=(User)req.getAttribute("user");if(u==null)throw new Problem(401,"请先登录");return u;}
    public User optional(HttpServletRequest req){return (User)req.getAttribute("user");}
    public String owner(HttpServletRequest req){return token(req.getSession());}
    public boolean preHandle(HttpServletRequest req,HttpServletResponse res,Object handler){
        res.setHeader("X-Content-Type-Options","nosniff");res.setHeader("Cache-Control","no-store");
        res.setHeader("Referrer-Policy","same-origin");res.setHeader("X-Frame-Options","DENY");
        if(!req.getRequestURI().startsWith("/api/"))return true;
        HttpSession session=req.getSession();
        String csrf=token(session);
        if(!Set.of("GET","HEAD","OPTIONS").contains(req.getMethod())){
            if(!java.security.MessageDigest.isEqual(csrf.getBytes(),Objects.toString(req.getHeader("X-CSRF-Token"),"").getBytes()))throw new Problem(403,"页面会话已更新，请刷新后重试");
        }
        Object uid=session.getAttribute("uid");
        if(uid!=null){Map<String,Object> m=s.optional("SELECT * FROM af_user WHERE id=? AND enabled=TRUE",uid);if(m==null){session.invalidate();throw new Problem(401,"账号已停用，请重新登录");}req.setAttribute("user",toUser(m));}
        String path=req.getRequestURI();
        boolean publicPath=Set.of("/api/session","/api/login","/api/anonymous/query").contains(path)||path.equals("/api/reports")&&req.getMethod().equals("POST")||path.startsWith("/api/articles")&&req.getMethod().equals("GET")||path.equals("/api/attachments")&&req.getMethod().equals("POST")||path.startsWith("/api/attachments/")&&req.getMethod().equals("GET");
        if(!publicPath&&optional(req)==null)throw new Problem(401,"请先登录");
        return true;
    }
    public User toUser(Map<String,Object> m){return new User(num(m,"id"),str(m,"username"),str(m,"display_name"),str(m,"role"),num(m,"org_id"),num(m,"community_id"),num(m,"victim_id"),yes(m,"verified"),yes(m,"admin"));}
    public Map<String,Object> login(HttpServletRequest req,Map<String,Object> b){
        String username=required(b,"username",60),password=required(b,"password",200);
        List<Long> f=failures.computeIfAbsent(username,k->Collections.synchronizedList(new ArrayList<>()));
        synchronized(f){f.removeIf(t->t<System.currentTimeMillis()-300000);if(f.size()>=8)throw new Problem(429,"尝试次数较多，请5分钟后重试");}
        Map<String,Object> m=s.optional("SELECT * FROM af_user WHERE username=?",username);
        if(m==null||!yes(m,"enabled")||!checkPassword(password,str(m,"password_hash"))||str(m,"role").equals("PAYMENT")&&num(m,"org_id")==0||str(m,"role").equals("GRID")&&num(m,"community_id")==0){f.add(System.currentTimeMillis());throw new Problem(401,"账号、密码或账号状态不正确");}
        failures.remove(username);req.getSession().invalidate();HttpSession session=req.getSession(true);session.setAttribute("uid",num(m,"id"));User u=toUser(m);s.log(u,"登录","account",u.id(),null,null,"登录成功");return map("user",u,"csrf",token(session));
    }
}
