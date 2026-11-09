package cn.course.antifraud;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.course.antifraud.Support.*;

class UrlRiskTest {
    private String key(String url){return Rules.normalize("网址",url);}
    @Test void unreservedEscapesAndDotSegments(){
        assertEquals(key("https://example.com/login"),key("https://example.com/a/%2e%2E/login"));
        assertEquals(key("https://example.com/~user"),key("https://example.com/%7euser"));
        assertEquals(key("https://example.com/ab"),key("https://example.com/%61b"));
    }
    @Test void encodedSeparatorsAreNotBlindlyDecoded(){
        assertEquals(key("https://example.com/a%2fb"),key("https://example.com/a%2Fb"));
        assertNotEquals(key("https://example.com/a/b"),key("https://example.com/a%2Fb"));
        assertNotEquals(key("https://example.com/a%252fb"),key("https://example.com/a%2fb"));
        assertNotEquals(key("https://example.com/A"),key("https://example.com/a"));
    }
    @Test void repeatedAndTrailingSlashesUseOnePathScope(){
        assertEquals(key("https://example.com/a/b"),key("https://example.com//a///b//"));
        assertEquals(key("https://example.com"),key("https://example.com/"));
    }
    @Test void domainAndIpv6Representations(){
        assertEquals(key("https://例子.测试/"),key("https://XN--FSQU00A.XN--0ZWM56D.:443/"));
        assertEquals(key("https://[2001:db8::1]/a"),key("https://[2001:0DB8:0:0:0:0:0:1]:443/a"));
        assertNotEquals(key("https://[2001:db8::1]/a"),key("https://[2001:db8::2]/a"));
        assertThrows(Problem.class,()->key("https://[fe80::1%25eth0]/"));
    }
    @Test void queryFragmentAndSchemeAreDeliberatelyOutsideRiskScope(){
        assertEquals(key("https://example.com/go?target=evil.example#x"),key("http://example.com/go?target=safe.example#y"));
        assertNotEquals(key("https://example.com/go"),key("https://example.com/go/next"));
        assertNotEquals(key("https://example.com/go"),key("https://example.com:8443/go"));
    }
    @Test void malformedAndAmbiguousAuthoritiesAreRejected(){
        for(String value:new String[]{"https://user:pass@example.com/","https://example.com/%zz","https://[::gg]/","file:///tmp/a","https://example.com\\@evil.example/"})assertThrows(Problem.class,()->key(value),value);
    }
}
