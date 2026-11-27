package com.hark.shiguang

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraNameTest {
    @Test fun modelAlreadyHasMake() {
        assertEquals("Xiaomi 13", CameraName.of("Xiaomi", "Xiaomi 13"))
        assertEquals("Canon EOS R5", CameraName.of("Canon", "Canon EOS R5"))
        assertEquals("HUAWEI P30", CameraName.of("huawei", "HUAWEI P30"))
    }
    @Test fun companySuffixStripped() {
        assertEquals("NIKON Z 6", CameraName.of("NIKON CORPORATION", "NIKON Z 6"))
        assertEquals("OLYMPUS E-M1", CameraName.of("OLYMPUS IMAGING CORP.", "E-M1"))
        assertEquals("OPPO PGT110", CameraName.of("OPPO Co., Ltd.", "PGT110"))
    }
    @Test fun differentMakeKept() {
        assertEquals("Apple iPhone 15 Pro", CameraName.of("Apple", "iPhone 15 Pro"))
        assertEquals("Xiaomi Redmi K60", CameraName.of("Xiaomi", "Redmi K60"))
        assertEquals("FUJIFILM X-T5", CameraName.of("FUJIFILM", "X-T5"))
        assertEquals("Leica Camera M11", CameraName.of("Leica Camera AG", "M11"))
        // 型号只是以厂商字母开头、不是同一个词时不吞
        assertEquals("LG LGE-X", CameraName.of("LG", "LGE-X"))
    }
    @Test fun blanks() {
        assertEquals("", CameraName.of("", ""))
        assertEquals("Pixel 8", CameraName.of(null, "Pixel 8"))
        assertEquals("SONY", CameraName.of("SONY", " "))
        assertEquals("CORPORATION", CameraName.brand("CORPORATION"))
    }
}
