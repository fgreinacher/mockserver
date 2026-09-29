#!/usr/bin/env bash
# Sourced by perf-test-run.sh and perf-test-allocprofile.sh. The JDK that reads the SUT's JFR: the
# SUT runs JDK 25, whose views and events an older `jfr` lacks, and the ceiling cut needs JDK 19+.
# Pinned to the multi-arch index digest (a per-arch manifest digest would break the other arch).
PERF_JFR_JDK_IMAGE="${PERF_JFR_JDK_IMAGE:-eclipse-temurin:25.0.4.1_1-jdk@sha256:119a3d18f160a3e7655a66034d0f43beee31cd7b3b9142d57a5de29772011de6}"
