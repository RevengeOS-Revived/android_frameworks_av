#
# Copyright 2017 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_PACKAGE_NAME := MediaComponents
LOCAL_MODULE_OWNER := google

# TODO: create a separate key for this package.
LOCAL_CERTIFICATE := platform

# TODO: Use System SDK once public APIs are approved
# LOCAL_SDK_VERSION := system_current

LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_PROGUARD_FLAG_FILES := proguard.cfg

LOCAL_MULTILIB := first

LOCAL_JAVA_LIBRARIES += android-support-annotations

# To embed native libraries in package, uncomment the lines below.
#LOCAL_MODULE_TAGS := samples
#LOCAL_JNI_SHARED_LIBRARIES := \
#    libaacextractor \
#    libamrextractor \
#    libflacextractor \
#    libmidiextractor \
#    libmkvextractor \
#    libmp3extractor \
#    libmp4extractor \
#    libmpeg2extractor \
#    liboggextractor \
#    libwavextractor \

# TODO: Remove dependency with other support libraries.
LOCAL_STATIC_ANDROID_LIBRARIES += \
    android-support-v4 \
    android-support-v7-appcompat \
    android-support-v7-palette
LOCAL_USE_AAPT2 := true

include $(BUILD_PACKAGE)

include $(call all-makefiles-under,$(LOCAL_PATH))
