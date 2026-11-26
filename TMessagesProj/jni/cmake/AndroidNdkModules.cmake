# Copyright (c) 2014, Pavel Rojtberg
# All rights reserved.
#
# Redistribution and use in source and binary forms, with or without
# modification, are permitted provided that the following conditions are met:
#
# 1. Redistributions of source code must retain the above copyright notice,
# this list of conditions and the following disclaimer.
#
# 2. Redistributions in binary form must reproduce the above copyright notice,
# this list of conditions and the following disclaimer in the documentation
# and/or other materials provided with the distribution.
#
# 3. Neither the name of the copyright holder nor the names of its
# contributors may be used to endorse or promote products derived from this
# software without specific prior written permission.
#
# THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
# AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
# IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
# ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
# LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
# CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
# SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
# INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
# CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
# ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
# POSSIBILITY OF SUCH DAMAGE.

# Compatibility copy of the Android NDK CMake helper historically shipped
# by the NDK. NDK r29 retains the cpufeatures source but not this helper file.
macro(android_ndk_import_module_cpufeatures)
    if(ANDROID)
        set(_godhead_cpufeatures_dir "${ANDROID_NDK}/sources/android/cpufeatures")
        if(NOT EXISTS "${_godhead_cpufeatures_dir}/cpu-features.c")
            message(FATAL_ERROR "Android NDK cpufeatures source is missing: ${_godhead_cpufeatures_dir}")
        endif()
        add_library(cpufeatures STATIC "${_godhead_cpufeatures_dir}/cpu-features.c")
        target_include_directories(cpufeatures PUBLIC "${_godhead_cpufeatures_dir}")
        target_link_libraries(cpufeatures PUBLIC dl)
    endif()
endmacro()
