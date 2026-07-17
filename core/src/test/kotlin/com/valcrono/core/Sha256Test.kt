package com.valcrono.core
import org.junit.Assert.*; import org.junit.Test
class Sha256Test{ @Test fun hashesStreaming(){ assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc".byteInputStream())) } }
