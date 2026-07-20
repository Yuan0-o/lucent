// Desktop compatibility shim for android.os.SystemClock. elapsedRealtime is a monotonic
// milliseconds clock; System.nanoTime provides exactly that on the JVM.
package android.os

object SystemClock {
    @JvmStatic fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000L
}
