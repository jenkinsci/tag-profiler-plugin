package org.jenkinsci.plugins.tagprofiler;

import hudson.Extension;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.RootAction;
import jenkins.model.Jenkins;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author stephenc
 * @since 14/02/2013 11:51
 */
@Extension
public final class ProfileStats implements RootAction {

    private final ConcurrentMap<String, Stats> runningStats = new ConcurrentHashMap<String, Stats>();

    private static final ThreadLocal<Measurement> measurement = new ThreadLocal<Measurement>();

    private static final Logger LOGGER = Logger.getLogger(ProfileStats.class.getName());

    public ProfileStats() {
    }

    public String getIconFileName() {
        return "/plugin/tag-profiler/images/24x24/clock.png";
    }

    public String getDisplayName() {
        return "Tag Profiler";
    }

    public String getUrlName() {
        return "tag-profiler";
    }

    public static void reset() {
        getInstance().runningStats.clear();
    }

    public List<Snapshot> getSnapshot() {
        List<Snapshot> result = new ArrayList<Snapshot>(runningStats.size());
        for (Stats s: runningStats.values()) {
            result.add(s.snapshot());
        }
        Collections.sort(result, new Comparator<Snapshot>() {
            public int compare(Snapshot o1, Snapshot o2) {
                return o1.getName().compareTo(o2.getName());
            }
        });
        return result;
    }

    public static void enter(String name) {
        name = hudson.Util.fixEmptyAndTrim(name);
        measurement.set(new Measurement(measurement.get(), name == null ? "anonymous" : name));
    }

    public static void leave() {
        long endTime = System.nanoTime();
        Measurement m = measurement.get();
        if (m != null) {
            Stats stats;
            final ProfileStats singleton = getInstance();
            while (null == (stats = singleton.runningStats.get(m.name))) {
                singleton.runningStats.putIfAbsent(m.name, new Stats(m.name));
            }
            long durationNanos = endTime - m.startTime;
            stats.record(durationNanos, m.childTime);
            if (m.parent != null) {
                m.parent.childTime += durationNanos;
            } else {
                final String name = m.name;
                new Thread() {
                    @Override
                    public void run() {
                        SortedMap<String, Stats> tree = new TreeMap<String, Stats>();
                        for (Map.Entry<String, Stats> entry : singleton.runningStats.entrySet()) {
                            if (entry.getKey().startsWith(name)) {
                                tree.put(entry.getKey(), entry.getValue());
                            }
                        }
                        for (Stats s : tree.values()) {
                            Snapshot snapshot = s.snapshot();
                            if (LOGGER.isLoggable(Level.INFO)) {
                                LOGGER.log(Level.INFO,
                                        "Profiler[{0}]\n    n: {1}\n    Total time: {2}±{3}s\n    Own time: "
                                                + "{4}±{5}s\n    Child time:"
                                                + " {6}±{7}s",
                                        new Object[]{
                                                snapshot.getName(),
                                                snapshot.getCount(),
                                                snapshot.getAvgTotalTime(),
                                                snapshot.getAvgTotalTimeStdDev(),
                                                snapshot.getAvgOwnTime(),
                                                snapshot.getAvgOwnTimeStdDev(),
                                                snapshot.getAvgChildTime(),
                                                snapshot.getAvgChildTimeStdDev()
                                        });
                            }
                        }
                    }
                }.start();
            }
            measurement.set(m.parent);
        }
    }

    private static ProfileStats getInstance() {
        return Jenkins.getInstance().getExtensionList(RootAction.class).get(ProfileStats.class);
    }

    public static final class Snapshot {
        private final String name;
        private final int count;
        private final double avgTotalTime;
        private final double avgTotalTimeStdDev;
        private final double avgOwnTime;
        private final double avgOwnTimeStdDev;
        private final double avgChildTime;
        private final double avgChildTimeStdDev;

        private Snapshot(String name, int count, double avgTotalTimeStdDev, double avgTotalTime,
                         double avgOwnTimeStdDev,
                         double avgOwnTime, double avgChildTimeStdDev, double avgChildTime) {
            this.name = name;
            this.count = count;
            this.avgTotalTimeStdDev = avgTotalTimeStdDev;
            this.avgTotalTime = avgTotalTime;
            this.avgOwnTimeStdDev = avgOwnTimeStdDev;
            this.avgOwnTime = avgOwnTime;
            this.avgChildTimeStdDev = avgChildTimeStdDev;
            this.avgChildTime = avgChildTime;
        }

        public String getName() {
            return name;
        }

        public int getCount() {
            return count;
        }

        public double getAvgTotalTimeStdDev() {
            return avgTotalTimeStdDev;
        }

        public double getAvgTotalTime() {
            return avgTotalTime;
        }

        public double getAvgOwnTimeStdDev() {
            return avgOwnTimeStdDev;
        }

        public double getAvgOwnTime() {
            return avgOwnTime;
        }

        public double getAvgChildTimeStdDev() {
            return avgChildTimeStdDev;
        }

        public double getAvgChildTime() {
            return avgChildTime;
        }
    }

    private static final class Stats {
        private final String name;
        private int count;
        private double totalSum;
        private double totalSum2;
        private double ownSum;
        private double ownSum2;
        private double childSum;
        private double childSum2;

        public Stats(String name) {
            this.name = name;
        }

        public synchronized void record(long totalNanos, long childNanos) {
            final double totalSecs = totalNanos * 1e-9;
            final double ownSecs = (totalNanos - childNanos) * 1e-9;
            final double childSecs = childNanos * 1e-9;
            count++;
            totalSum += totalSecs;
            totalSum2 += totalSecs * totalSecs;
            ownSum += ownSecs;
            ownSum2 += ownSecs * ownSecs;
            childSum += childSecs;
            childSum2 += childSecs * childSecs;
        }

        public synchronized Snapshot snapshot() {
            return new Snapshot(name, count, totalSum / count,
                    stddev(totalSum, totalSum2),
                    ownSum / count,
                    stddev(ownSum, ownSum2),
                    childSum / count,
                    stddev(childSum, childSum2)
            );
        }

        private double stddev(double sum, double sum2) {
            return count < 2 ? Double.NaN : Math.sqrt((count * sum2 - sum * sum) / (count * (count - 1)));
        }
    }

    private static final class Measurement {
        private final Measurement parent;
        private final String name;
        private final long startTime;
        private long childTime;

        private Measurement(Measurement parent, String name) {
            this.parent = parent;
            this.name = parent == null ? name : parent.name + "/" + name;
            startTime = System.nanoTime();
            childTime = 0;
        }
    }
}
