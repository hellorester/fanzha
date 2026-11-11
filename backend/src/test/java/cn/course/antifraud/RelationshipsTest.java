package cn.course.antifraud;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RelationshipsTest {
    @Test void equivalentIdentityFormatsNormalizeToTheSameValue(){
        assertEquals("11010519900101123X",Relationships.normalizeIdentity(" １１０１０５-１９９００１０１-１２３ｘ "));
        assertEquals("PASSPORT123",Relationships.normalizeIdentity(" passport_123 "));
    }
}
