/* How many channels a sink opens with, and which speakers a device layout names.
 *
 * WHY THIS SUITE EXISTS. The output unit's input scope takes any channel count and reports
 * success, so a count nobody bounded plays its first channels and drops the rest in silence. The
 * sink therefore bounds the count asked for by the device's channel count and by the speakers the
 * device's layout names. Neither bound can be seen on a stereo Mac, so the two rules are driven
 * here with layouts no machine in the room has: an eight channel receiver set up as stereo, an
 * audio interface with eight unnamed outputs, a 7.1 receiver.
 *
 * The last case opens the real default output and asks for eight channels, to prove the open path
 * uses the same rule. It makes no sound: the sink is never started.
 */

#include "harness.h"
#include "kite_rt.h"
#include "kite_rt_sink_internal.h"

#include <stddef.h>
#include <stdint.h>
#include <string.h>

#if defined(__APPLE__)
#include <AudioToolbox/AudioToolbox.h>

/* A layout of up to eight described channels, as the unit hands one back. */
typedef union {
    AudioChannelLayout layout;
    uint8_t bytes[offsetof(AudioChannelLayout, mChannelDescriptions) + 8 * sizeof(AudioChannelDescription)];
} described_layout;

static uint32_t describe(described_layout *out, const AudioChannelLabel *labels, uint32_t count)
{
    uint32_t i;
    memset(out, 0, sizeof(*out));
    out->layout.mChannelLayoutTag = kAudioChannelLayoutTag_UseChannelDescriptions;
    out->layout.mNumberChannelDescriptions = count;
    for (i = 0; i < count; i++)
        out->layout.mChannelDescriptions[i].mChannelLabel = labels[i];
    return (uint32_t)(offsetof(AudioChannelLayout, mChannelDescriptions) + count * sizeof(AudioChannelDescription));
}
#endif

int main(void)
{
    kt_suite_begin("test_sink_channels");

#if !defined(__APPLE__)
    kt_case("the channel suite has no device layout to read off Apple platforms");
    kt_partial("AudioChannelLayout exists only on Apple platforms");
    return kt_suite_end();
#else

    kt_case("the count asked for is kept inside one to eight");
    {
        KT_EQ_INT(kprt_sink_bound_channels(0, 0, -1), 1);
        KT_EQ_INT(kprt_sink_bound_channels(-3, 0, -1), 1);
        KT_EQ_INT(kprt_sink_bound_channels(2, 0, -1), 2);
        KT_EQ_INT(kprt_sink_bound_channels(6, 0, -1), 6);
        KT_EQ_INT(kprt_sink_bound_channels(7, 0, -1), 7);
        KT_EQ_INT(kprt_sink_bound_channels(8, 0, -1), 8);
        /* Twelve channels are 7.1 with four height speakers, and the first eight are its bed. */
        KT_EQ_INT(kprt_sink_bound_channels(12, 0, -1), 8);
    }

    kt_case("the device's channel count bounds the count");
    {
        KT_EQ_INT(kprt_sink_bound_channels(8, 8, -1), 8);
        KT_EQ_INT(kprt_sink_bound_channels(8, 6, -1), 6);
        KT_EQ_INT(kprt_sink_bound_channels(6, 2, -1), 2);
        KT_EQ_INT(kprt_sink_bound_channels(6, 8, -1), 6);
        KT_EQ_INT(kprt_sink_bound_channels(2, 1, -1), 1);
    }

    kt_case("the speakers the device names bound the count, and never below stereo");
    {
        /* An eight channel receiver that the system holds as stereo plays two speakers. */
        KT_EQ_INT(kprt_sink_bound_channels(6, 8, 2), 2);
        KT_EQ_INT(kprt_sink_bound_channels(8, 8, 6), 6);
        KT_EQ_INT(kprt_sink_bound_channels(8, 8, 8), 8);
        /* Eight outputs with no name: the first pair is where the system puts stereo. */
        KT_EQ_INT(kprt_sink_bound_channels(6, 8, 0), 2);
        KT_EQ_INT(kprt_sink_bound_channels(6, 8, 1), 2);
        /* A mono stream stays mono, and a mono device stays mono. */
        KT_EQ_INT(kprt_sink_bound_channels(1, 8, 0), 1);
        KT_EQ_INT(kprt_sink_bound_channels(6, 1, 0), 1);
        /* More speakers than were asked for change nothing. */
        KT_EQ_INT(kprt_sink_bound_channels(2, 8, 8), 2);
    }

    kt_case("a layout names the channels that carry a speaker label");
    {
        static const AudioChannelLabel stereo_of_eight[] = {
            kAudioChannelLabel_Left, kAudioChannelLabel_Right,
            kAudioChannelLabel_Unknown, kAudioChannelLabel_Unknown, kAudioChannelLabel_Unknown,
            kAudioChannelLabel_Unknown, kAudioChannelLabel_Unknown, kAudioChannelLabel_Unknown,
        };
        static const AudioChannelLabel surround[] = {
            kAudioChannelLabel_Left, kAudioChannelLabel_Right, kAudioChannelLabel_Center,
            kAudioChannelLabel_LFEScreen, kAudioChannelLabel_LeftSurround, kAudioChannelLabel_RightSurround,
            kAudioChannelLabel_RearSurroundLeft, kAudioChannelLabel_RearSurroundRight,
        };
        static const AudioChannelLabel outputs[] = {
            kAudioChannelLabel_Discrete_0, kAudioChannelLabel_Discrete_0 + 1, kAudioChannelLabel_Discrete,
            kAudioChannelLabel_Unused, kAudioChannelLabel_Unknown, kAudioChannelLabel_Discrete_0 + 5,
        };
        described_layout layout;
        uint32_t size;

        size = describe(&layout, stereo_of_eight, 8);
        KT_EQ_INT(kprt_layout_speakers(&layout.layout, size), 2);
        size = describe(&layout, surround, 8);
        KT_EQ_INT(kprt_layout_speakers(&layout.layout, size), 8);
        size = describe(&layout, surround, 6);
        KT_EQ_INT(kprt_layout_speakers(&layout.layout, size), 6);
        size = describe(&layout, outputs, 6);
        KT_EQ_INT(kprt_layout_speakers(&layout.layout, size), 0);
    }

    kt_case("a layout given as a tag or a bitmap names what it counts");
    {
        AudioChannelLayout layout;
        const uint32_t size = (uint32_t)sizeof(layout);

        memset(&layout, 0, sizeof(layout));
        layout.mChannelLayoutTag = kAudioChannelLayoutTag_Stereo;
        KT_EQ_INT(kprt_layout_speakers(&layout, size), 2);
        layout.mChannelLayoutTag = kAudioChannelLayoutTag_MPEG_5_1_A;
        KT_EQ_INT(kprt_layout_speakers(&layout, size), 6);
        layout.mChannelLayoutTag = kAudioChannelLayoutTag_WAVE_7_1;
        KT_EQ_INT(kprt_layout_speakers(&layout, size), 8);
        /* Eight channels in order with no speaker named, and eight of an unknown kind. */
        layout.mChannelLayoutTag = kAudioChannelLayoutTag_DiscreteInOrder | 8;
        KT_EQ_INT(kprt_layout_speakers(&layout, size), 0);
        layout.mChannelLayoutTag = kAudioChannelLayoutTag_Unknown | 8;
        KT_EQ_INT(kprt_layout_speakers(&layout, size), 0);
        layout.mChannelLayoutTag = kAudioChannelLayoutTag_UseChannelBitmap;
        layout.mChannelBitmap = kAudioChannelBit_Left | kAudioChannelBit_Right | kAudioChannelBit_Center |
                                kAudioChannelBit_LFEScreen | kAudioChannelBit_LeftSurround |
                                kAudioChannelBit_RightSurround;
        KT_EQ_INT(kprt_layout_speakers(&layout, size), 6);
    }

    kt_case("a layout that is missing or cut short is read no further than it goes");
    {
        static const AudioChannelLabel surround[] = {
            kAudioChannelLabel_Left, kAudioChannelLabel_Right, kAudioChannelLabel_Center,
            kAudioChannelLabel_LFEScreen, kAudioChannelLabel_LeftSurround, kAudioChannelLabel_RightSurround,
        };
        described_layout layout;
        uint32_t size = describe(&layout, surround, 6);

        KT_EQ_INT(kprt_layout_speakers(NULL, size), -1);
        KT_EQ_INT(kprt_layout_speakers(&layout.layout, 4), -1);
        /* The count claims six and the answer held two. */
        size = (uint32_t)(offsetof(AudioChannelLayout, mChannelDescriptions) + 2 * sizeof(AudioChannelDescription));
        KT_EQ_INT(kprt_layout_speakers(&layout.layout, size), 2);
    }

    kt_case("the default output opens with no more channels than it has, in an order it was told");
    {
        kprt_sink *sink = NULL;
        kprt_sink_format accepted;
        int32_t os_status = 0;
        int32_t verdict = kprt_sink_create(48000, 8, &sink, &accepted, &os_status);

        if (verdict != KPRT_SINK_OK) {
            kt_partial("this machine opened no default output: verdict %d, status %d", verdict, os_status);
        } else {
            kt_detail("asked for 8 channels, opened with %d, layout mask 0x%llx",
                      accepted.channels, (unsigned long long)accepted.channel_layout_mask);
            KT_CHECKF(accepted.channels >= 1 && accepted.channels <= 8, "opened with %d channels", accepted.channels);
            if (accepted.channels == 8 && accepted.channel_layout_mask != 0)
                KT_EQ_I64(accepted.channel_layout_mask, 0x63F);
            if (accepted.channels == 7 && accepted.channel_layout_mask != 0)
                KT_EQ_I64(accepted.channel_layout_mask, 0x70F);
            if (accepted.channels == 2 && accepted.channel_layout_mask != 0)
                KT_EQ_I64(accepted.channel_layout_mask, 0x3);
            KT_EQ_INT(kprt_sink_destroy(sink), KPRT_SINK_OK);
        }
    }

    return kt_suite_end();
#endif
}
