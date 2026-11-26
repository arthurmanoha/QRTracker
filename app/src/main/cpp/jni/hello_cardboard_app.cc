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

#include "hello_cardboard_app.h"

#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>

#include <array>
#include <cmath>
#include <fstream>

#include "cardboard.h"

namespace ndk_hello_cardboard {

namespace {

// The objects are about 1 meter in radius, so the min/max target distance are
// set so that the objects are always within the room (which is about 5 meters
// across) and the reticle is always closer than any objects.
constexpr float kMinTargetDistance = 2.5f;
constexpr float kMaxTargetDistance = 3.5f;
constexpr float kMinTargetHeight = 0.5f;
constexpr float kMaxTargetHeight = kMinTargetHeight + 3.0f;

constexpr float kDefaultFloorHeight = -1.7f;

// 6 Hz cutoff frequency for the velocity filter of the head tracker.
constexpr int kVelocityFilterCutoffFrequency = 6;

constexpr uint64_t kPredictionTimeWithoutVsyncNanos = 50000000;

// Angle threshold for determining whether the controller is pointing at the
// object.
constexpr float kAngleLimit = 0.2f;

// Number of different possible targets
constexpr int kTargetMeshCount = 3;

// Simple shaders to render .obj files without any lighting.
constexpr const char* kObjVertexShader =
    R"glsl(
    uniform mat4 u_MVP;
    attribute vec4 a_Position;
    attribute vec2 a_UV;
    varying vec2 v_UV;

    void main() {
      v_UV = a_UV;
      gl_Position = u_MVP * a_Position;
    })glsl";

    constexpr const char* kObjFragmentShader =
            R"glsl(
    precision mediump float;

    uniform sampler2D u_Texture;
    uniform int u_UseTexture;
    varying vec2 v_UV;

    void main() {
      if (u_UseTexture == 1) {
        gl_FragColor = texture2D(
            u_Texture,
            vec2(v_UV.x, 1.0 - v_UV.y)
        );
      } else {
        gl_FragColor = vec4(1.0, 0.0, 0.0, 1.0);
      }
    })glsl";

}  // anonymous namespace

HelloCardboardApp::HelloCardboardApp(JavaVM* vm, jobject obj,
                                     jobject asset_mgr_obj)
    : head_tracker_(nullptr),
      lens_distortion_(nullptr),
      distortion_renderer_(nullptr),
      screen_params_changed_(false),
      device_params_changed_(false),
      screen_width_(0),
      screen_height_(0),
      depthRenderBuffer_(0),
      framebuffer_(0),
      texture_(0),
      obj_program_(0),
      obj_position_param_(0),
      obj_uv_param_(0),
      obj_modelview_projection_param_(0),
      target_object_meshes_(kTargetMeshCount),
      target_object_not_selected_textures_(kTargetMeshCount),
      target_object_selected_textures_(kTargetMeshCount),
      cur_target_object_(RandomUniformInt(kTargetMeshCount)) {
  JNIEnv* env;
  vm->GetEnv((void**)&env, JNI_VERSION_1_6);
  java_asset_mgr_ = env->NewGlobalRef(asset_mgr_obj);
  asset_mgr_ = AAssetManager_fromJava(env, asset_mgr_obj);

  Cardboard_initializeAndroid(vm, obj);
  head_tracker_ = CardboardHeadTracker_create();
  CardboardHeadTracker_setLowPassFilter(head_tracker_,
                                        kVelocityFilterCutoffFrequency);
}

HelloCardboardApp::~HelloCardboardApp() {
  CardboardHeadTracker_destroy(head_tracker_);
  CardboardLensDistortion_destroy(lens_distortion_);
  CardboardDistortionRenderer_destroy(distortion_renderer_);
}

void HelloCardboardApp::OnSurfaceCreated(JNIEnv* env) {
  const int obj_vertex_shader =
      LoadGLShader(GL_VERTEX_SHADER, kObjVertexShader);
  const int obj_fragment_shader =
      LoadGLShader(GL_FRAGMENT_SHADER, kObjFragmentShader);

  obj_program_ = glCreateProgram();
  glAttachShader(obj_program_, obj_vertex_shader);
  glAttachShader(obj_program_, obj_fragment_shader);
  glLinkProgram(obj_program_);
  glUseProgram(obj_program_);

  CHECKGLERROR("Obj program");

  obj_position_param_ = glGetAttribLocation(obj_program_, "a_Position");
  obj_uv_param_ = glGetAttribLocation(obj_program_, "a_UV");
  obj_modelview_projection_param_ = glGetUniformLocation(obj_program_, "u_MVP");
  obj_use_texture_param_ =
          glGetUniformLocation(obj_program_, "u_UseTexture");

  CHECKGLERROR("Obj program params");

  // Géométrie du cube représentant un marqueur.
  // Cube de 2 cm de côté.
  const float h = 0.01f;

  marker_vertices_ = {
          -h, -h, -h,  // 0
          h, -h, -h,  // 1
          h,  h, -h,  // 2
          -h,  h, -h,  // 3
          -h, -h,  h,  // 4
          h, -h,  h,  // 5
          h,  h,  h,  // 6
          -h,  h,  h   // 7
  };

  marker_indices_ = {
          // Face arrière
          0, 2, 1, 0, 3, 2,
          // Face avant
          4, 5, 6, 4, 6, 7,
          // Face gauche
          0, 4, 7, 0, 7, 3,
          // Face droite
          1, 2, 6, 1, 6, 5,
          // Face inférieure
          0, 1, 5, 0, 5, 4,
          // Face supérieure
          3, 7, 6, 3, 6, 2
  };

  HELLOCARDBOARD_CHECK(room_.Initialize(obj_position_param_, obj_uv_param_,
                                        "CubeRoom.obj", asset_mgr_));
  HELLOCARDBOARD_CHECK(
      room_tex_.Initialize(env, java_asset_mgr_, "CubeRoom_BakedDiffuse.png"));
  HELLOCARDBOARD_CHECK(target_object_meshes_[0].Initialize(
      obj_position_param_, obj_uv_param_, "Icosahedron.obj", asset_mgr_));
  HELLOCARDBOARD_CHECK(target_object_not_selected_textures_[0].Initialize(
      env, java_asset_mgr_, "Icosahedron_Blue_BakedDiffuse.png"));
  HELLOCARDBOARD_CHECK(target_object_selected_textures_[0].Initialize(
      env, java_asset_mgr_, "Icosahedron_Pink_BakedDiffuse.png"));
  HELLOCARDBOARD_CHECK(target_object_meshes_[1].Initialize(
      obj_position_param_, obj_uv_param_, "QuadSphere.obj", asset_mgr_));
  HELLOCARDBOARD_CHECK(target_object_not_selected_textures_[1].Initialize(
      env, java_asset_mgr_, "QuadSphere_Blue_BakedDiffuse.png"));
  HELLOCARDBOARD_CHECK(target_object_selected_textures_[1].Initialize(
      env, java_asset_mgr_, "QuadSphere_Pink_BakedDiffuse.png"));
  HELLOCARDBOARD_CHECK(target_object_meshes_[2].Initialize(
      obj_position_param_, obj_uv_param_, "TriSphere.obj", asset_mgr_));
  HELLOCARDBOARD_CHECK(target_object_not_selected_textures_[2].Initialize(
      env, java_asset_mgr_, "TriSphere_Blue_BakedDiffuse.png"));
  HELLOCARDBOARD_CHECK(target_object_selected_textures_[2].Initialize(
      env, java_asset_mgr_, "TriSphere_Pink_BakedDiffuse.png"));

  // Target object first appears directly in front of user.
  model_target_ = GetTranslationMatrix({0.0f, 1.5f, kMinTargetDistance});

  CHECKGLERROR("OnSurfaceCreated");
}

void HelloCardboardApp::SetScreenParams(int width, int height) {
  screen_width_ = width;
  screen_height_ = height;
  screen_params_changed_ = true;
}

static Matrix4x4 MarkerToMatrix(const VrMarker& marker) {

  Matrix4x4 model_matrix = {};

  // Rotation : conversion du tableau ligne par ligne
  // vers le stockage m[colonne][ligne].
  for (int row = 0; row < 3; ++row) {
    for (int col = 0; col < 3; ++col) {
      model_matrix.m[row][col] =
              marker.rotation[col * 3 + row];
    }
  }

  // Translation.
  model_matrix.m[3][0] = marker.position[0];
  model_matrix.m[3][1] = marker.position[1];
  model_matrix.m[3][2] = marker.position[2];
  model_matrix.m[3][3] = 1.0f;

  return model_matrix;
}

static Matrix4x4 GetOpenCvToCardboardMatrix() {

  Matrix4x4 conversion = {};

  conversion.m[0][0] =  1.0f;
  conversion.m[1][1] = -1.0f;
  conversion.m[2][2] = -1.0f;
  conversion.m[3][3] =  1.0f;

  return conversion;
}

static Matrix4x4 GetQrToOpenGlMatrix() {
    Matrix4x4 conversion = {};

    conversion.m[0][0] = 1.0f;
    conversion.m[2][1] = 1.0f;
    conversion.m[1][2] = -1.0f;
    conversion.m[3][3] = 1.0f;

    return conversion;
}

static Matrix4x4 InvertRigidTransform(const Matrix4x4& transform) {
    Matrix4x4 inverse = {};

    // Rotation inverse : transposée.
    for (int col = 0; col < 3; ++col) {
        for (int row = 0; row < 3; ++row) {
            inverse.m[col][row] = transform.m[row][col];
        }
    }

    // Translation inverse : -R^T * t.
    const float tx = transform.m[3][0];
    const float ty = transform.m[3][1];
    const float tz = transform.m[3][2];

    for (int row = 0; row < 3; ++row) {
        inverse.m[3][row] = -(
                inverse.m[0][row] * tx +
                inverse.m[1][row] * ty +
                inverse.m[2][row] * tz
        );
    }

    inverse.m[3][3] = 1.0f;
    return inverse;
}

void HelloCardboardApp::SetMarkers(
            const std::vector<VrMarker>& markers,
            int64_t image_timestamp) {

        markers_ = markers;
        marker_timestamp_ = image_timestamp;



  const VrMarker* origin_marker = nullptr;

  for (const auto& marker : markers_) {
    if (marker.id == 0) {
      origin_marker = &marker;
      break;
    }
  }

  if (origin_marker == nullptr) {
    __android_log_print(
            ANDROID_LOG_WARN,
            "QRTracker2",
            "Marqueur origine ID=0 absent");
    return;
  }
    // Transformation du marqueur 0 vers la caméra.
    Matrix4x4 marker_to_camera = MarkerToMatrix(*origin_marker);

// Inversion de la transformation : caméra vers monde QRTracker.
    Matrix4x4 camera_to_world = {};

// Rotation inverse : transposée de la rotation du marqueur.
    for (int col = 0; col < 3; ++col) {
        for (int row = 0; row < 3; ++row) {
            camera_to_world.m[col][row] =
                    marker_to_camera.m[row][col];
        }
    }

// Position de la caméra dans le repère du marqueur 0.
    const float tx = marker_to_camera.m[3][0];
    const float ty = marker_to_camera.m[3][1];
    const float tz = marker_to_camera.m[3][2];

    camera_to_world.m[3][0] = -(
            marker_to_camera.m[0][0] * tx +
            marker_to_camera.m[0][1] * ty +
            marker_to_camera.m[0][2] * tz);

    camera_to_world.m[3][1] = -(
            marker_to_camera.m[1][0] * tx +
            marker_to_camera.m[1][1] * ty +
            marker_to_camera.m[1][2] * tz);

    camera_to_world.m[3][2] = -(
            marker_to_camera.m[2][0] * tx +
            marker_to_camera.m[2][1] * ty +
            marker_to_camera.m[2][2] * tz);

    camera_to_world.m[3][3] = 1.0f;

// Mémorisation de la pose caméra.
    camera_world_pose_ = camera_to_world;
    camera_world_pose_valid_ = true;

    __android_log_print(
            ANDROID_LOG_DEBUG,
            "QRTracker2",
            "Pose caméra QR : x=%.4f y=%.4f z=%.4f",
            camera_world_pose_.m[3][0],
            camera_world_pose_.m[3][1],
            camera_world_pose_.m[3][2]);

    __android_log_print(
            ANDROID_LOG_DEBUG,
            "QRTracker2 nnn",
            "Rotation caméra QR :");
    for (int row = 0; row < 3; ++row) {
        __android_log_print(
                ANDROID_LOG_DEBUG,
                "QRTracker2 nnn",
                "%.4f  %.4f  %.4f",
                camera_world_pose_.m[0][row],
                camera_world_pose_.m[1][row],
                camera_world_pose_.m[2][row]);
    }

//  __android_log_print(
//          ANDROID_LOG_DEBUG,
//          "QRTracker2",
//          "Marqueur origine trouvé : x=%.4f y=%.4f z=%.4f",
//          origin_marker->position[0],
//          origin_marker->position[1],
//          origin_marker->position[2]);
//
//  __android_log_print(
//          ANDROID_LOG_DEBUG,
//          "QRTracker2",
//          "Horodatage image stocké : %lld ns",
//          static_cast<long long>(marker_timestamp_));
//
//  __android_log_print(
//          ANDROID_LOG_DEBUG,
//          "QRTracker2",
//          "Marqueurs reçus : %zu",
//          markers_.size());
//
//  for (const auto& marker : markers_) {
//    __android_log_print(
//            ANDROID_LOG_DEBUG,
//            "QRTracker2",
//            "ID=%d : x=%.4f y=%.4f z=%.4f",
//            marker.id,
//            marker.position[0],
//            marker.position[1],
//            marker.position[2]);
//  }

}

void HelloCardboardApp::OnDrawFrame() {
  if (!UpdateDeviceParams()) {
    return;
  }

    // Vue calculée à partir de la pose OpenCV du marqueur 0.
    if (camera_world_pose_valid_) {
        head_view_ =
                GetOpenCvToCardboardMatrix() *
                InvertRigidTransform(camera_world_pose_) *
                InvertRigidTransform(GetQrToOpenGlMatrix());
    }

  // Bind buffer
  glBindFramebuffer(GL_FRAMEBUFFER, framebuffer_);

  glEnable(GL_DEPTH_TEST);
  glEnable(GL_CULL_FACE);
  glDisable(GL_SCISSOR_TEST);
  glEnable(GL_BLEND);
  glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
  glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

  // Draw eyes views
  for (int eye = 0; eye < 2; ++eye) {
    glViewport(eye == kLeft ? 0 : screen_width_ / 2, 0, screen_width_ / 2,
               screen_height_);

    Matrix4x4 eye_matrix = GetMatrixFromGlArray(eye_matrices_[eye]);
    Matrix4x4 eye_view = eye_matrix * head_view_;

    Matrix4x4 projection_matrix =
        GetMatrixFromGlArray(projection_matrices_[eye]);
    Matrix4x4 modelview_target = eye_view * model_target_;
    modelview_projection_target_ = projection_matrix * modelview_target;
    modelview_projection_room_ = projection_matrix * eye_view;

    // Draw room and target
    DrawWorld();

    DrawMarkers(eye_view, projection_matrix);
  }

  // Render
  CardboardDistortionRenderer_renderEyeToDisplay(
      distortion_renderer_, /* target_display = */ 0, /* x = */ 0, /* y = */ 0,
      screen_width_, screen_height_, &left_eye_texture_description_,
      &right_eye_texture_description_);

  CHECKGLERROR("onDrawFrame");
}

void HelloCardboardApp::OnTriggerEvent() {
  if (IsPointingAtTarget()) {
    HideTarget();
  }
}

void HelloCardboardApp::OnPause() { CardboardHeadTracker_pause(head_tracker_); }

void HelloCardboardApp::OnResume() {
  CardboardHeadTracker_resume(head_tracker_);

  // Parameters may have changed.
  device_params_changed_ = true;

  // Check for device parameters existence in external storage. If they're
  // missing, we must scan a Cardboard QR code and save the obtained parameters.
  uint8_t* buffer;
  int size;
  CardboardQrCode_getSavedDeviceParams(&buffer, &size);
  if (size == 0) {
    SwitchViewer();
  }
  CardboardQrCode_destroy(buffer);
}

void HelloCardboardApp::SwitchViewer() {
  CardboardQrCode_scanQrCodeAndSaveDeviceParams();
}

bool HelloCardboardApp::UpdateDeviceParams() {
  // Checks if screen or device parameters changed
  if (!screen_params_changed_ && !device_params_changed_) {
    return true;
  }

  // Get saved device parameters
  uint8_t* buffer;
  int size;
  CardboardQrCode_getSavedDeviceParams(&buffer, &size);

  // If there are no parameters saved yet, returns false.
  if (size == 0) {
    return false;
  }

  CardboardLensDistortion_destroy(lens_distortion_);
  lens_distortion_ = CardboardLensDistortion_create(buffer, size, screen_width_,
                                                    screen_height_);

  CardboardQrCode_destroy(buffer);

  GlSetup();

  CardboardDistortionRenderer_destroy(distortion_renderer_);
  const CardboardOpenGlEsDistortionRendererConfig config{kGlTexture2D};
  distortion_renderer_ = CardboardOpenGlEs2DistortionRenderer_create(&config);

  CardboardMesh left_mesh;
  CardboardMesh right_mesh;
  CardboardLensDistortion_getDistortionMesh(lens_distortion_, kLeft,
                                            &left_mesh);
  CardboardLensDistortion_getDistortionMesh(lens_distortion_, kRight,
                                            &right_mesh);

  CardboardDistortionRenderer_setMesh(distortion_renderer_, &left_mesh, kLeft);
  CardboardDistortionRenderer_setMesh(distortion_renderer_, &right_mesh,
                                      kRight);

  // Get eye matrices
  CardboardLensDistortion_getEyeFromHeadMatrix(lens_distortion_, kLeft,
                                               eye_matrices_[0]);
  CardboardLensDistortion_getEyeFromHeadMatrix(lens_distortion_, kRight,
                                               eye_matrices_[1]);
  CardboardLensDistortion_getProjectionMatrix(lens_distortion_, kLeft, kZNear,
                                              kZFar, projection_matrices_[0]);
  CardboardLensDistortion_getProjectionMatrix(lens_distortion_, kRight, kZNear,
                                              kZFar, projection_matrices_[1]);

  screen_params_changed_ = false;
  device_params_changed_ = false;

  CHECKGLERROR("UpdateDeviceParams");

  return true;
}

void HelloCardboardApp::GlSetup() {
  LOGD("GL SETUP");

  if (framebuffer_ != 0) {
    GlTeardown();
  }

  // Create render texture.
  glGenTextures(1, &texture_);
  glBindTexture(GL_TEXTURE_2D, texture_);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

  glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, screen_width_, screen_height_, 0,
               GL_RGB, GL_UNSIGNED_BYTE, 0);

  left_eye_texture_description_.texture = texture_;
  left_eye_texture_description_.left_u = 0;
  left_eye_texture_description_.right_u = 0.5;
  left_eye_texture_description_.top_v = 1;
  left_eye_texture_description_.bottom_v = 0;

  right_eye_texture_description_.texture = texture_;
  right_eye_texture_description_.left_u = 0.5;
  right_eye_texture_description_.right_u = 1;
  right_eye_texture_description_.top_v = 1;
  right_eye_texture_description_.bottom_v = 0;

  // Generate depth buffer to perform depth test.
  glGenRenderbuffers(1, &depthRenderBuffer_);
  glBindRenderbuffer(GL_RENDERBUFFER, depthRenderBuffer_);
  glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT16, screen_width_,
                        screen_height_);
  CHECKGLERROR("Create Render buffer");

  // Create render target.
  glGenFramebuffers(1, &framebuffer_);
  glBindFramebuffer(GL_FRAMEBUFFER, framebuffer_);
  glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                         texture_, 0);
  glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                            GL_RENDERBUFFER, depthRenderBuffer_);

  CHECKGLERROR("GlSetup");
}

void HelloCardboardApp::GlTeardown() {
  if (framebuffer_ == 0) {
    return;
  }
  glDeleteRenderbuffers(1, &depthRenderBuffer_);
  depthRenderBuffer_ = 0;
  glDeleteFramebuffers(1, &framebuffer_);
  framebuffer_ = 0;
  glDeleteTextures(1, &texture_);
  texture_ = 0;

  CHECKGLERROR("GlTeardown");
}

Matrix4x4 HelloCardboardApp::GetPose() {
  std::array<float, 4> out_orientation;
  std::array<float, 3> out_position;

  CardboardHeadTracker_getPose(
          head_tracker_, GetBootTimeNano() + kPredictionTimeWithoutVsyncNanos,
          kLandscapeLeft, &out_position[0], &out_orientation[0]);

  // Position de la tête
  for (int i = 0; i < 3; ++i) {
    head_pose_[i] = out_position[i];
  }

  // Orientation de la tête : quaternion X, Y, Z, W
  for (int i = 0; i < 4; ++i) {
    head_pose_[i + 3] = out_orientation[i];
  }

  return GetTranslationMatrix(out_position) *
         Quatf::FromXYZW(&out_orientation[0]).ToMatrix();
}

Matrix4x4 HelloCardboardApp::GetPoseAtTimestamp(int64_t timestamp_ns) {
  std::array<float, 4> out_orientation;
  std::array<float, 3> out_position;

  CardboardHeadTracker_getPose(
          head_tracker_,
          timestamp_ns,
          kLandscapeLeft,
          &out_position[0],
          &out_orientation[0]);

  Quatf rotation = Quatf::FromXYZW(&out_orientation[0]);

  return GetTranslationMatrix(out_position) * rotation.ToMatrix();
}

void HelloCardboardApp::DrawWorld() {
  DrawRoom();
  DrawTarget();
}

void HelloCardboardApp::DrawTarget() {
  glUseProgram(obj_program_);
  glUniform1i(obj_use_texture_param_, 1);

  std::array<float, 16> target_array = modelview_projection_target_.ToGlArray();
  glUniformMatrix4fv(obj_modelview_projection_param_, 1, GL_FALSE,
                     target_array.data());

  if (IsPointingAtTarget()) {
    target_object_selected_textures_[cur_target_object_].Bind();
  } else {
    target_object_not_selected_textures_[cur_target_object_].Bind();
  }
  target_object_meshes_[cur_target_object_].Draw();

  CHECKGLERROR("DrawTarget");
}

void HelloCardboardApp::DrawMarkers(
        Matrix4x4& eye_view,
        Matrix4x4& projection_matrix) {

  if (markers_.empty()) {
    return;
  }

  glUseProgram(obj_program_);
  glUniform1i(obj_use_texture_param_, 0);

  glEnableVertexAttribArray(obj_position_param_);
  glVertexAttribPointer(
          obj_position_param_,
          3,
          GL_FLOAT,
          GL_FALSE,
          0,
          marker_vertices_.data());

  glDisableVertexAttribArray(obj_uv_param_);
  glVertexAttrib2f(obj_uv_param_, 0.0f, 0.0f);

  for (const auto& marker : markers_) {

    Matrix4x4 model_matrix = {};

    // Matrice de rotation.
    // Les données Kotlin sont stockées en ordre ligne par ligne.
    for (int row = 0; row < 3; ++row) {
      for (int col = 0; col < 3; ++col) {
        model_matrix.m[row][col] =
                marker.rotation[col * 3 + row];
      }
    }

    // Translation.
    model_matrix.m[3][0] = marker.position[0];
    model_matrix.m[3][1] = marker.position[1];
    model_matrix.m[3][2] = marker.position[2];
    model_matrix.m[3][3] = 1.0f;

    // Matrice finale de transformation.
    Matrix4x4 modelview = eye_view * model_matrix;
    Matrix4x4 mvp = projection_matrix * modelview;

    std::array<float, 16> mvp_array = mvp.ToGlArray();

    glUniformMatrix4fv(
            obj_modelview_projection_param_,
            1,
            GL_FALSE,
            mvp_array.data());

    glDrawElements(
            GL_TRIANGLES,
            marker_indices_.size(),
            GL_UNSIGNED_SHORT,
            marker_indices_.data());
  }

  CHECKGLERROR("DrawMarkers");
}

void HelloCardboardApp::DrawRoom() {
  glUseProgram(obj_program_);
  glUniform1i(obj_use_texture_param_, 1);

  std::array<float, 16> room_array = modelview_projection_room_.ToGlArray();
  glUniformMatrix4fv(obj_modelview_projection_param_, 1, GL_FALSE,
                     room_array.data());

  room_tex_.Bind();
  room_.Draw();

  CHECKGLERROR("DrawRoom");
}

void HelloCardboardApp::HideTarget() {
  cur_target_object_ = RandomUniformInt(kTargetMeshCount);

  float angle = RandomUniformFloat(-M_PI, M_PI);
  float distance = RandomUniformFloat(kMinTargetDistance, kMaxTargetDistance);
  float height = RandomUniformFloat(kMinTargetHeight, kMaxTargetHeight);
  std::array<float, 3> target_position = {std::cos(angle) * distance, height,
                                          std::sin(angle) * distance};

  model_target_ = GetTranslationMatrix(target_position);
}

bool HelloCardboardApp::IsPointingAtTarget() {
  // Compute vectors pointing towards the reticle and towards the target object
  // in head space.
  Matrix4x4 head_from_target = head_view_ * model_target_;

  const std::array<float, 4> unit_quaternion = {0.f, 0.f, 0.f, 1.f};
  const std::array<float, 4> point_vector = {0.f, 0.f, -1.f, 0.f};
  const std::array<float, 4> target_vector = head_from_target * unit_quaternion;

  float angle = AngleBetweenVectors(point_vector, target_vector);
  return angle < kAngleLimit;
}

}  // namespace ndk_hello_cardboard
