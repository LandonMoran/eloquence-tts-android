#pragma once
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_ERROR 6
/** Discard Android log messages in host native tests and report zero characters written. */
static inline int __android_log_print(int priority, const char *tag, const char *format, ...) { return 0; }
