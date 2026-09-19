import io.github.yuroyami.kiteplayer.audioviz.SpectralPower;
import java.lang.management.ManagementFactory;
import java.util.Locale;

/** JDK 21 CPU probe. Run the same compiled harness against each candidate audioviz jar. */
public class KiteSpectralProfile {
    static volatile float consumed;

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        var bean = ManagementFactory.getThreadMXBean();
        bean.setThreadCpuTimeEnabled(true);
        for (int channels : new int[] {1, 2, 3, 6}) {
            var power = new SpectralPower(2048, 48000, 40);
            float[][] samples = new float[channels][2048];
            for (int channel = 0; channel < channels; channel++) {
                for (int index = 0; index < 2048; index++) {
                    samples[channel][index] = (float) (.3 * Math.sin(2 * Math.PI * (91.7 + channel * 193) * index / 48000));
                }
            }
            for (int index = 0; index < 30000; index++) {
                power.measure(samples, index % 2048);
                consumed = power.getTotalPower();
            }
            int count = 30000;
            long start = bean.getCurrentThreadCpuTime();
            for (int index = 0; index < count; index++) {
                power.measure(samples, index % 2048);
                consumed = power.getTotalPower();
            }
            double millis = (bean.getCurrentThreadCpuTime() - start) / 1e6 / count;
            System.out.printf("{\"channels\":%d,\"measurements\":%d,\"meanCpuMs\":%.6f,\"lastPower\":%.6f}%n",
                channels, count, millis, consumed);
        }
    }
}
