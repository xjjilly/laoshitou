// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2026 老石头 contributors

#include "audio/JavaMediaSource.h"

#include <algorithm>
#include <cstddef>

namespace audio {
namespace {

// Attaches the calling native thread to the JVM for the duration of one call.
// The decode thread is a std::thread, so it is not attached by default.
class JniEnvScope {
 public:
  explicit JniEnvScope(JavaVM* vm) : vm_(vm) {
    if (vm_ == nullptr) {
      return;
    }
    if (vm_->GetEnv(reinterpret_cast<void**>(&env_), JNI_VERSION_1_6) == JNI_EDETACHED) {
      if (vm_->AttachCurrentThread(&env_, nullptr) == JNI_OK) {
        attached_ = true;
      } else {
        env_ = nullptr;
      }
    }
  }

  ~JniEnvScope() {
    if (attached_) {
      vm_->DetachCurrentThread();
    }
  }

  JNIEnv* env() const { return env_; }

 private:
  JavaVM* vm_ = nullptr;
  JNIEnv* env_ = nullptr;
  bool attached_ = false;
};

}  // namespace

JavaMediaSource::JavaMediaSource(JNIEnv* env, jobject data_source) {
  if (env == nullptr || data_source == nullptr) {
    error_ = "MediaDataSource is null";
    return;
  }
  if (env->GetJavaVM(&vm_) != JNI_OK || vm_ == nullptr) {
    error_ = "GetJavaVM failed";
    return;
  }
  data_source_ = env->NewGlobalRef(data_source);
  if (data_source_ == nullptr) {
    error_ = "NewGlobalRef failed";
    return;
  }
  jclass cls = env->GetObjectClass(data_source_);
  if (cls == nullptr) {
    error_ = "GetObjectClass failed";
    return;
  }
  read_at_ = env->GetMethodID(cls, "readAt", "(J[BII)I");
  get_size_ = env->GetMethodID(cls, "getSize", "()J");
  close_ = env->GetMethodID(cls, "close", "()V");
  env->DeleteLocalRef(cls);
  if (read_at_ == nullptr || get_size_ == nullptr || close_ == nullptr) {
    env->ExceptionClear();
    error_ = "MediaDataSource methods are unavailable";
    return;
  }
  buffer_ = static_cast<jbyteArray>(env->NewGlobalRef(env->NewByteArray(kBufferBytes)));
  if (buffer_ == nullptr) {
    error_ = "NewByteArray failed";
  }
}

JavaMediaSource::~JavaMediaSource() {
  JniEnvScope scope(vm_);
  JNIEnv* env = scope.env();
  if (env != nullptr) {
    if (data_source_ != nullptr && close_ != nullptr) {
      env->CallVoidMethod(data_source_, close_);
      if (env->ExceptionCheck()) {
        env->ExceptionClear();
      }
    }
    if (data_source_ != nullptr) {
      env->DeleteGlobalRef(data_source_);
    }
    if (buffer_ != nullptr) {
      env->DeleteGlobalRef(buffer_);
    }
  }
}

int JavaMediaSource::read_at(std::int64_t position, void* buffer, int size) {
  if (size <= 0) {
    return 0;
  }
  if (buffer == nullptr || data_source_ == nullptr || read_at_ == nullptr ||
      buffer_ == nullptr) {
    error_ = "MediaDataSource is not usable";
    return -1;
  }
  JniEnvScope scope(vm_);
  JNIEnv* env = scope.env();
  if (env == nullptr) {
    error_ = "JNI attach failed";
    return -1;
  }

  const int chunk = std::min(size, kBufferBytes);
  const jint read = env->CallIntMethod(data_source_, read_at_, static_cast<jlong>(position),
                                       buffer_, 0, chunk);
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    error_ = "MediaDataSource.readAt failed";
    return -1;
  }
  if (read < 0) {
    return 0;
  }
  if (read == 0) {
    return 0;
  }
  if (read > chunk) {
    error_ = "MediaDataSource.readAt returned too many bytes";
    return -1;
  }
  env->GetByteArrayRegion(buffer_, 0, read, static_cast<jbyte*>(buffer));
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    error_ = "MediaDataSource buffer copy failed";
    return -1;
  }
  return static_cast<int>(read);
}

std::int64_t JavaMediaSource::size() {
  if (data_source_ == nullptr || get_size_ == nullptr) {
    return -1;
  }
  JniEnvScope scope(vm_);
  JNIEnv* env = scope.env();
  if (env == nullptr) {
    error_ = "JNI attach failed";
    return -1;
  }
  const jlong value = env->CallLongMethod(data_source_, get_size_);
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    error_ = "MediaDataSource.getSize failed";
    return -1;
  }
  return static_cast<std::int64_t>(value);
}

std::string JavaMediaSource::last_error() const { return error_; }

}  // namespace audio
