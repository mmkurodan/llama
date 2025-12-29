# JNI Llama Integration

This directory contains the JNI (Java Native Interface) wrapper for integrating llama.cpp (gguf-0.17.1) with Android applications.

## Features

- Model loading and initialization
- Text generation with customizable sampling parameters
- Detailed logging to external storage
- Download support with progress callbacks

## Usage

### Setting Up Logging

To enable detailed logging to a file:

```java
LlamaNative llamaNative = new LlamaNative();
String logPath = getExternalFilesDir(null).getAbsolutePath() + "/ollama.log";
llamaNative.setLogPath(logPath);
```

The log file will contain:
- Timestamped entries for all operations
- Model file validation (size, header dump)
- Initialization timing
- Token-by-token generation details
- Error messages with context

### Initializing the Model

```java
String modelPath = "/path/to/model.gguf";
String result = llamaNative.init(modelPath);
if (!"ok".equals(result)) {
    Log.e(TAG, "Model init failed: " + result);
}
```

The init method will:
- Validate the model file exists and is readable
- Dump the first 64 bytes of the file header for debugging
- Load the model using llama.cpp
- Create a context with specified parameters
- Log timing information for diagnostics

### Generating Text

```java
String prompt = "Once upon a time";
String output = llamaNative.generate(prompt);
```

### Cleaning Up Resources

**Important**: Always call `free()` when done to release native resources:

```java
llamaNative.free();
```

This will:
- Free the context
- Free the model
- Clean up the llama backend
- Close the log file

Failing to call `free()` may result in memory leaks and resource exhaustion.

## Logging Details

The JNI code provides extensive logging for debugging:

### Header Dump
On initialization, the first 64 bytes of the GGUF file are dumped in hex and ASCII format to help diagnose file format issues.

### Detailed Errors
Model load failures include:
- Model path and length
- File size in bytes
- Time elapsed during load attempt
- System error codes and messages

### Token-Level Logging
During generation, each token is logged with:
- Token ID
- Decoded text piece
- Iteration number

### Thread-Safe Logging
All logging uses mutex protection and is safe to call from callback threads (e.g., download progress).

## Configuration

Default generation parameters (defined in `jni_llama.cpp`):
- Context size: 512 tokens
- Threads: 2
- Batch size: 16
- Temperature: 0.7
- Top-P: 0.9
- Top-K: 40

These can be modified in the source if needed.

## Compatibility Notes

This implementation uses llama.cpp API version compatible with gguf-0.17.1:
- Uses `llama_model_load_from_file` instead of deprecated `llama_load_model_from_file`
- Uses `llama_init_from_model` instead of deprecated `llama_new_context_with_model`
- Uses new sampler chain API instead of deprecated `llama_sample_*` functions
- Uses batch-based `llama_decode` instead of deprecated `llama_eval`
- Uses vocab-based tokenization and token conversion APIs

## Building

The JNI library is built as part of the Android project. See `app/src/main/cpp/CMakeLists.txt` for build configuration.

## Troubleshooting

### Model fails to load

Check the log file for:
1. File size - ensure the model file is complete
2. Header dump - verify it starts with "GGUF" magic bytes
3. Load time - if it returns quickly (< 1 second), the file format may be incompatible

### Memory issues

Always call `free()` when switching models or exiting the application. Each model may use significant memory depending on size and quantization.

### Thread safety

The implementation uses mutexes to protect global state. However, avoid calling `init()` or `free()` from multiple threads simultaneously.
