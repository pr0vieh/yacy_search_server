package net.yacy.tools.optimizer;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Progress reporter with nice formatting and ETA calculation
 */
public class ProgressReporter {

    private int currentPhase;
    private String currentPhaseTitle;
    private long phaseStartTime;
    private long totalStartTime;
    private double phaseProgress; // 0.0 to 1.0
    private String phaseDetails = "";
    private int progressBarWidth = 40;
    private boolean progressBarActive = false;
    private String lastProgressLine = "";

    public ProgressReporter() {
        this.totalStartTime = System.currentTimeMillis();
    }

    public void startPhase(int phase, String title) {
        this.currentPhase = phase;
        this.currentPhaseTitle = title;
        this.phaseStartTime = System.currentTimeMillis();
        this.phaseProgress = 0.0;
        this.phaseDetails = "";
        clearProgressBar();
        
        System.out.println("\n[" + phase + "/5] " + title);
        this.progressBarActive = false;
    }

    public void updateProgress(double progress, String details) {
        this.phaseProgress = Math.min(1.0, Math.max(0.0, progress));
        this.phaseDetails = details != null ? details : "";
        printProgressBar();
    }

    public void updateProgress(double progress) {
        updateProgress(progress, "");
    }

    public void completePhase(String details) {
        this.phaseProgress = 1.0;
        this.phaseDetails = details != null ? details : "";
        printProgressBar();
        clearProgressBar();
        System.out.println("\n      ✓ " + this.phaseDetails);
    }

    private void printProgressBar() {
        long elapsed = System.currentTimeMillis() - phaseStartTime;
        String progressBar = buildProgressBar();
        int percent = (int) (phaseProgress * 100);
        
        String eta = "";
        if (phaseProgress > 0 && phaseProgress < 1.0) {
            long estimatedTotal = (long) (elapsed / phaseProgress);
            long estimatedRemaining = estimatedTotal - elapsed;
            eta = " | " + formatDuration(estimatedRemaining) + " remaining";
        } else if (phaseProgress == 1.0) {
            eta = " | " + formatDuration(elapsed);
        }

        StringBuilder line = new StringBuilder();
        line.append("      ").append(progressBar);
        line.append(" ").append(String.format("%3d%%", percent));
        line.append(eta);
        if (!phaseDetails.isEmpty()) {
            line.append(" - ").append(phaseDetails);
        }
        
        // Clear line with spaces if shorter than last line
        String currentLine = line.toString();
        if (currentLine.length() < lastProgressLine.length()) {
            int spacesToAdd = lastProgressLine.length() - currentLine.length();
            for (int i = 0; i < spacesToAdd; i++) {
                line.append(' ');
            }
        }
        lastProgressLine = currentLine;
        
        System.out.print("\r" + line.toString());
        System.out.flush();
        this.progressBarActive = true;
    }
    
    private void clearProgressBar() {
        if (this.progressBarActive) {
            // Clear the progress bar line
            System.out.print("\r");
            for (int i = 0; i < lastProgressLine.length(); i++) {
                System.out.print(' ');
            }
            System.out.print("\r");
            System.out.flush();
            this.progressBarActive = false;
            this.lastProgressLine = "";
        }
    }

    private String buildProgressBar() {
        int filled = (int) (phaseProgress * progressBarWidth);
        int empty = progressBarWidth - filled;
        
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < filled; i++) {
            sb.append("█");
        }
        for (int i = 0; i < empty; i++) {
            sb.append("░");
        }
        sb.append("]");
        return sb.toString();
    }

    public void info(String message) {
        clearProgressBar();
        System.out.println("      ℹ " + message);
    }

    public void error(String message) {
        clearProgressBar();
        System.out.println("      ✗ ERROR: " + message);
    }

    public void success(String message) {
        clearProgressBar();
        System.out.println("      ✓ " + message);
    }

    private String formatDuration(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        
        if (hours > 0) {
            return String.format("%dh %dm", hours, minutes % 60);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds % 60);
        } else {
            return String.format("%ds", seconds);
        }
    }

    public long getTotalElapsedTime() {
        return System.currentTimeMillis() - totalStartTime;
    }
}
