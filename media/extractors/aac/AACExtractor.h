/*
 * Copyright (C) 2011 The Android Open Source Project
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

#ifndef AAC_EXTRACTOR_H_

#define AAC_EXTRACTOR_H_

#include <media/MediaExtractorPluginApi.h>
#include <media/MediaExtractorPluginHelper.h>
#include <media/NdkMediaFormat.h>

#include <utils/Vector.h>

namespace android {

struct AMessage;
class String8;

class AACExtractor : public MediaExtractorPluginHelperV3 {
public:
    AACExtractor(DataSourceHelper *source, off64_t offset);

    virtual size_t countTracks();
    virtual MediaTrackHelperV3 *getTrack(size_t index);
    virtual media_status_t getTrackMetaData(AMediaFormat *meta, size_t index, uint32_t flags);

    virtual media_status_t getMetaData(AMediaFormat *meta);
    virtual const char * name() { return "AACExtractor"; }

protected:
    virtual ~AACExtractor();

private:
    DataSourceHelper *mDataSource;
    AMediaFormat *mMeta;
    status_t mInitCheck;

    Vector<uint64_t> mOffsetVector;
    int64_t mFrameDurationUs;

    AACExtractor(const AACExtractor &);
    AACExtractor &operator=(const AACExtractor &);
};

bool SniffAAC(
        DataSourceHelper *source, String8 *mimeType, float *confidence, off64_t *offset);

}  // namespace android

#endif  // AAC_EXTRACTOR_H_
