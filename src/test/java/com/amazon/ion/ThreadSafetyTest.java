package com.amazon.ion;

import com.amazon.ion.system.IonBinaryWriterBuilder;
import com.amazon.ion.system.IonSystemBuilder;
import org.junit.jupiter.api.Test;
import com.amazon.ion.IonSystem;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class ThreadSafetyTest {
    @Test
    void testThreadSafety() throws IOException {

        IonStruct originalStruct = IonSystemBuilder.standard().build().newEmptyStruct();
        for (int i = 0; i < 10; i++) {
            originalStruct.add("field" + i, IonSystemBuilder.standard().build().singleValue("value"+i));
        }
        IonStruct cloneStruct = originalStruct.clone();
        cloneStruct.makeReadOnly();
    }
}
