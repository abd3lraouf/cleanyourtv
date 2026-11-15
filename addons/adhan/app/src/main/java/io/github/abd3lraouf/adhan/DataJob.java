package io.github.abd3lraouf.adhan;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Runs when Prayer Times reports new times; reschedule (which also sets this watch again). */
public final class DataJob extends JobService {
    @Override public boolean onStartJob(JobParameters params) {
        Scheduler.reschedule(this);
        return false;
    }

    @Override public boolean onStopJob(JobParameters params) {
        return false;
    }
}
