package cn.course.antifraud;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static cn.course.antifraud.Support.*;

class AuthTest {
    private static final String PASSWORD_HASH=password("LoginTest2026!");
    private Auth authFor(String role){
        Support support=mock(Support.class);
        when(support.optional("SELECT * FROM af_user WHERE username=?","account")).thenReturn(map("id",7,"username","account","display_name","登录验证","role",role,"enabled",true,"password_hash",PASSWORD_HASH,"org_id",1,"community_id",1));
        return new Auth(support);
    }
    private Map<String,Object> credentials(String role){return map("username","account","password","LoginTest2026!","role",role);}
    @ParameterizedTest @ValueSource(strings={"PUBLIC","CENTER","PAYMENT","GRID"})
    void matchingIdentityLogsIntoStoredRole(String role){
        var request=new MockHttpServletRequest();
        var result=authFor(role).login(request,credentials(role));
        assertEquals(role,((User)result.get("user")).role());
        assertEquals(7L,request.getSession().getAttribute("uid"));
    }
    @ParameterizedTest @ValueSource(strings={"PUBLIC","CENTER","PAYMENT","GRID"})
    void choosingAnotherIdentityNeverCreatesSession(String storedRole){
        var request=new MockHttpServletRequest();
        String selected=storedRole.equals("CENTER")?"PUBLIC":"CENTER";
        var error=assertThrows(Problem.class,()->authFor(storedRole).login(request,credentials(selected)));
        assertEquals(403,error.status);
        assertNull(request.getSession(false));
    }
    @Test void unsupportedIdentityIsRejected(){
        var request=new MockHttpServletRequest();
        var error=assertThrows(Problem.class,()->authFor("PUBLIC").login(request,credentials("ADMIN")));
        assertEquals(400,error.status);
        assertNull(request.getSession(false));
    }
    @Test void identitySelectionDoesNotBypassPassword(){
        var request=new MockHttpServletRequest();var body=credentials("CENTER");body.put("password","wrong");
        var error=assertThrows(Problem.class,()->authFor("CENTER").login(request,body));
        assertEquals(401,error.status);
        assertNull(request.getSession(false));
    }
    @Test void ExistingClientsWithoutIdentityStillUseStoredRole(){
        var request=new MockHttpServletRequest();var body=credentials("PUBLIC");body.remove("role");
        assertEquals("PUBLIC",((User)authFor("PUBLIC").login(request,body).get("user")).role());
    }
}
