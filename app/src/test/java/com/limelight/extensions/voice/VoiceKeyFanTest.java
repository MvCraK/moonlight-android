// EXTENSION DEVELOPMENT [EXT-REMOTE-VOICE] [ADDED]
package com.limelight.extensions.voice;

import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceKeyFanTest {
    @Test public void allAnchorsStayInsideUsableAreaAndDoNotOverlap() {
        int[][] windows = {{320,260},{660,260},{320,640},{640,320},{320,144},{640,100},
                {280,190},{360,244},{375,667},{100,330},{180,100},{70,680}};
        for (float density : new float[]{1,1.5f,2.625f,3}) {
            int size = Math.round(48*density);
            for (int[] window : windows) {
                int w=Math.round(window[0]*density), h=Math.round(window[1]*density);
                for (int step=0;step<=100;step++) {
                    int y = Math.round((h-size)*step/100f);
                    VoiceKeyFan left = new VoiceKeyFan(), right = new VoiceKeyFan();
                    left.place(w,h,size,y,false,density);
                    right.place(w,h,size,y,true,density);
                    assertTrue(left.keySize>0);
                    assertEquals(left.keySize,right.keySize);
                    if (w>=300*density && h>=100*density) assertEquals(size,left.keySize);
                    for (int i=0;i<4;i++) {
                        int x=left.x[i], top=left.y[i], side=left.keySize;
                        assertTrue(x>=0 && x+side<=w && top>=0 && top+side<=h);
                        assertEquals(w-x-side,right.x[i]); assertEquals(top,right.y[i]);
                        assertTrue(x>=size || top>=y+size || top+side<=y);
                        for (int j=0;j<i;j++) {
                            assertTrue(Math.max(Math.abs(x-left.x[j]),Math.abs(top-left.y[j]))>=side);
                        }
                    }
                }
            }
        }
    }
}
