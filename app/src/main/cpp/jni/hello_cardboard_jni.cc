/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <android/log.h>
#include <jni.h>

#include <memory>
#include <cstdint>
#include <vector>

#include "hello_cardboard_app.h"

#define JNI_METHOD(return_type, method_name) \
  JNIEXPORT return_type JNICALL              \
      Java_com_example_qrtracker_VrActivity_##method_name

namespace {

inline jlong jptr(ndk_hello_cardboard::HelloCardboardApp* native_app) {
  return reinterpret_cast<intptr_t>(native_app);
}

inline ndk_hello_cardboard::HelloCardboardApp* native(jlong ptr) {
  return reinterpret_cast<ndk_hello_cardboard::HelloCardboardApp*>(ptr);
}

JavaVM* javaVm;

}  // anonymous namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
  javaVm = vm;
  return JNI_VERSION_1_6;
}

JNI_METHOD(jlong, nativeOnCreate)
(JNIEnv* /*env*/, jobject obj, jobject asset_mgr) {
  return jptr(new ndk_hello_cardboard::HelloCardboardApp(javaVm, obj, asset_mgr));
}

JNI_METHOD(void, nativeOnDestroy)
(JNIEnv* /*env*/, jobject /*obj*/, jlong native_app) {
  delete native(native_app);
}

JNI_METHOD(void, nativeOnSurfaceCreated)
(JNIEnv* env, jobject /*obj*/, jlong native_app) {
  native(native_app)->OnSurfaceCreated(env);
}

JNI_METHOD(void, nativeOnDrawFrame)
(JNIEnv* /*env*/, jobject /*obj*/, jlong native_app) {
  native(native_app)->OnDrawFrame();
}

JNI_METHOD(void, nativeOnTriggerEvent)
(JNIEnv* /*env*/, jobject /*obj*/, jlong native_app) {
  native(native_app)->OnTriggerEvent();
}

JNI_METHOD(void, nativeOnPause)
(JNIEnv* /*env*/, jobject /*obj*/, jlong native_app) {
  native(native_app)->OnPause();
}

JNI_METHOD(void, nativeOnResume)
(JNIEnv* /*env*/, jobject /*obj*/, jlong native_app) {
  native(native_app)->OnResume();
}

JNI_METHOD(void, nativeSetScreenParams)
(JNIEnv* /*env*/, jobject /*obj*/, jlong native_app, jint width, jint height) {
  native(native_app)->SetScreenParams(width, height);
}

JNI_METHOD(void, nativeSetMarkers)
(JNIEnv* env, jobject /*obj*/, jlong native_app,
 jlong image_timestamp,
 jintArray ids, jfloatArray positions, jfloatArray rotations) {

  if (native_app == 0 || ids == nullptr ||
      positions == nullptr || rotations == nullptr) {
    return;
  }
    __android_log_print(
            ANDROID_LOG_DEBUG,
            "QRTracker2",
            "Horodatage image reçu en JNI : %lld ns",
            static_cast<long long>(image_timestamp));

  const jsize count = env->GetArrayLength(ids);

  if (env->GetArrayLength(positions) != count * 3 ||
      env->GetArrayLength(rotations) != count * 9) {
    __android_log_print(
            ANDROID_LOG_ERROR,
            "QRTracker2",
            "Tailles de tableaux JNI invalides");
    return;
  }

  std::vector<jint> markerIds(count);
  std::vector<jfloat> markerPositions(count * 3);
  std::vector<jfloat> markerRotations(count * 9);

  env->GetIntArrayRegion(ids, 0, count, markerIds.data());
  env->GetFloatArrayRegion(
          positions, 0, count * 3, markerPositions.data());
  env->GetFloatArrayRegion(
          rotations, 0, count * 9, markerRotations.data());

  std::vector<ndk_hello_cardboard::VrMarker> markers(count);

  for (jsize i = 0; i < count; ++i) {
    markers[i].id = markerIds[i];

    for (int j = 0; j < 3; ++j) {
      markers[i].position[j] = markerPositions[i * 3 + j];
    }

    for (int j = 0; j < 9; ++j) {
      markers[i].rotation[j] = markerRotations[i * 9 + j];
    }
  }

  native(native_app)->SetMarkers(markers, image_timestamp);
}

JNI_METHOD(void, nativeSwitchViewer)
(JNIEnv* /*env*/, jobject /*obj*/, jlong native_app) {
  native(native_app)->SwitchViewer();
}

}  // extern "C"
