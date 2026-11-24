LOCAL_PATH := $(call my-dir)

# The modem sources are the web app's, used as-is.
RIBBIT_DSP := $(LOCAL_PATH)/../../webassembly/dsp

# libribbit.so: plain armeabi (ARMv5TE, soft-float). Runs on any ARM phone.
include $(CLEAR_VARS)
LOCAL_MODULE := ribbit
LOCAL_SRC_FILES := ribbit_jni.cpp ribbit_crypto.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/shim $(LOCAL_PATH) $(RIBBIT_DSP)
LOCAL_ARM_MODE := arm
LOCAL_CFLAGS := -O2 -DNDEBUG -D__STDC_LIMIT_MACROS -D__STDC_CONSTANT_MACROS
LOCAL_CPPFLAGS := -std=gnu++1z
include $(BUILD_SHARED_LIBRARY)

# libribbit_vfp.so: same code for ARMv6 + VFP (e.g. the ARM1136 in the
# Galaxy Y Pro), still armeabi/softfp calling convention. The app loads this
# one when /proc/cpuinfo lists "vfp", which makes the float DSP much faster.
include $(CLEAR_VARS)
LOCAL_MODULE := ribbit_vfp
LOCAL_SRC_FILES := ribbit_jni.cpp ribbit_crypto.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/shim $(LOCAL_PATH) $(RIBBIT_DSP)
LOCAL_ARM_MODE := arm
LOCAL_CFLAGS := -O2 -DNDEBUG -D__STDC_LIMIT_MACROS -D__STDC_CONSTANT_MACROS -march=armv6 -mfpu=vfp -mfloat-abi=softfp
LOCAL_CPPFLAGS := -std=gnu++1z
include $(BUILD_SHARED_LIBRARY)
