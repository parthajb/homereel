# NanoHTTPD and Media3 ship what R8 needs; nothing to add yet.

# libtorrent4j calls into Java from its native code (SWIG), so R8 must keep its classes as they are.
-keep class org.libtorrent4j.** { *; }
