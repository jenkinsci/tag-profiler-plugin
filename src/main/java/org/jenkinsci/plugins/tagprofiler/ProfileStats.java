/*
 * The MIT License
 *
 * Copyright (c) 2013, CloudBees, Inc., Stephen Connolly.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkinsci.plugins.tagprofiler;

import hudson.Extension;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.RootAction;
import hudson.util.PluginServletFilter;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.StaplerProxy;
import org.kohsuke.stapler.StaplerRequest;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A profiler for profiling Jenkins requests.
 */
@Extension(ordinal = Double.MAX_VALUE)
public final class ProfileStats extends Descriptor<ProfileStats> implements Describable<ProfileStats> {

    private final ConcurrentMap<String, Stats> runningStats = new ConcurrentHashMap<String, Stats>();

    private static final ThreadLocal<Measurement> measurement = new ThreadLocal<Measurement>();

    private static final Logger LOGGER = Logger.getLogger(ProfileStats.class.getName());

    public ProfileStats() {
        super(ProfileStats.class);
        try {
            PluginServletFilter.addFilter(new FilterImpl());
        } catch (ServletException e) {
            LOGGER.log(Level.WARNING, "Could not install request profiling", e);
        }
    }

    public String getDisplayName() {
        return "Tag Profiler";
    }

    public HttpResponse doReset(StaplerRequest req) {
        reset();
        return HttpResponses.redirectToDot();
    }

    public static void reset() {
        getInstance().runningStats.clear();
    }

    public List<Snapshot> getSnapshot() {
        List<Snapshot> result = new ArrayList<Snapshot>(runningStats.size());
        for (Stats s : runningStats.values()) {
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
        enter(name, "/");
    }

    static void enter(String name, String sep) {
        name = hudson.Util.fixEmptyAndTrim(name);
        measurement.set(new Measurement(measurement.get(), name == null ? "anonymous" : name, sep));
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
            if (durationNanos > 0) {
                stats.record(durationNanos, m.childTime);
                if (m.parent != null) {
                    m.parent.childTime += durationNanos;
                }
            }
            measurement.set(m.parent);
        }
    }

    private static ProfileStats getInstance() {
        return (ProfileStats) Jenkins.getInstance().getDescriptorOrDie(ProfileStats.class);
    }

    public ProfileStats getDescriptor() {
        return (ProfileStats) Jenkins.getInstance().getDescriptorOrDie(ProfileStats.class);
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

        private Snapshot(String name, int count, double avgTotalTime, double avgTotalTimeStdDev,
                         double avgOwnTime, double avgOwnTimeStdDev, double avgChildTime, double avgChildTimeStdDev) {
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

        public double getAvgTotalTimeStdDevMS() {
            return Math.round(avgTotalTimeStdDev * 10000.0) / 10.0;
        }

        public double getAvgTotalTimeMS() {
            return Math.round(avgTotalTime * 10000.0) / 10.0;
        }

        public double getAvgOwnTimeStdDevMS() {
            return Math.round(avgOwnTimeStdDev * 10000.0) / 10.0;
        }

        public double getAvgOwnTimeMS() {
            return Math.round(avgOwnTime * 10000.0) / 10.0;
        }

        public double getAvgChildTimeStdDevMS() {
            return Math.round(avgChildTimeStdDev * 10000.0) / 10.0;
        }

        public double getAvgChildTimeMS() {
            return Math.round(avgChildTime * 10000.0) / 10.0;
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();
            sb.append("Snapshot");
            sb.append("{name='").append(name).append('\'');
            sb.append(", count=").append(count);
            sb.append(", avgTotalTime=").append(avgTotalTime);
            sb.append(", avgOwnTime=").append(avgOwnTime);
            sb.append(", avgChildTime=").append(avgChildTime);
            sb.append('}');
            return sb.toString();
        }
    }

    private static final class Stats {
        private final String name;
        private int count = 0;
        private double totalSum = 0.0;
        private double totalSum2 = 0.0;
        private double ownSum = 0.0;
        private double ownSum2 = 0.0;
        private double childSum = 0.0;
        private double childSum2 = 0.0;

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
            return count == 0
                    ? new Snapshot(name, count, 0, Double.NaN, 0, Double.NaN, 0, Double.NaN)
                    : new Snapshot(name, count, totalSum / count,
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
        private final String sep;
        private final long startTime;
        private long childTime;

        private Measurement(Measurement parent, String name, String sep) {
            this.parent = parent;
            this.sep = sep == null ? "/" : sep;
            this.name = parent == null ? name : parent.name + parent.sep + name;
            startTime = System.nanoTime();
            childTime = 0;
        }
    }

    @Extension
    public static class RootActionImpl implements StaplerProxy, RootAction {

        public ProfileStats getTarget() {
            return (ProfileStats) Jenkins.getInstance().getDescriptorOrDie(ProfileStats.class);
        }

        public String getDisplayName() {
            return getTarget().getDisplayName();
        }

        public String getIconFileName() {
            return "/plugin/tag-profiler/images/24x24/clock.png";
        }

        public String getUrlName() {
            return "tag-profiler";
        }

    }

    @Extension
    public static class FilterImpl implements Filter {

        public void init(FilterConfig filterConfig) throws ServletException {
        }

        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            final HttpServletRequest req = (HttpServletRequest) request;
            final String requestURI = req.getRequestURI();
            final String name;
            if (requestURI.startsWith("/static/")) {
                name = "/static/*";
            } else if (requestURI.startsWith("/adjuncts/")) {
                name = "/adjuncts/*";
            } else if (requestURI.startsWith("/resources/")) {
                name = "/resources/*";
            } else {
                name = requestURI;
            }
            enter(name, " » ");
            try {
                chain.doFilter(request, response);
            } finally {
                leave();
            }
        }

        public void destroy() {
        }
    }
}
