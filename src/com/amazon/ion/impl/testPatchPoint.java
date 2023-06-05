package com.amazon.ion.impl;

import com.amazon.ion.IonType;
import com.amazon.ion.IonWriter;
import com.amazon.ion.system.IonSystemBuilder;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public class testPatchPoint {

    @Test
    public void testFunction() throws IOException {
        OutputStream outputStream = new FileOutputStream(new File("./generated.10n"));
        IonWriter iw = IonSystemBuilder.standard().build().newBinaryWriter(outputStream);
        iw.stepIn(IonType.LIST);
        int i = 0;
        while(i < 8000) {
            int j = 0;
            iw.stepIn(IonType.STRUCT);
            while( j < 200) {
                iw.setFieldName("a");
                iw.writeString("value");
                j++;
            }
            iw.stepOut();
            i++;
        }
        iw.stepOut();
        iw.close();
    }
}
