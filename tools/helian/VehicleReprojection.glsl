// UTM's two-car protocol uses one of the packed parallax bits as the car slot.
// Include after unpackM3P13; the remaining twelve bits retain parallax depth.
uniform mat4 rtmViewToPreviousView;
uniform mat4 rtmViewToPreviousView2;

float packRtmParallax(float depth, int slot) {
    float quantizedDepth = round(clamp(depth, 0.0, 1.0) * 4095.0);
    return (quantizedDepth * 2.0 + float(slot == 2)) / 8191.0;
}

float unpackRtmParallax(float packedDepth) {
    return floor(round(packedDepth * 8191.0) * 0.5) / 4095.0;
}

int rtmTemporalSlot(ivec2 texel) {
    float packedDepth = unpackM3P13(texelFetch(colortex2, texel, 0).a).y;
    return (int(round(packedDepth * 8191.0)) & 1) + 1;
}

mat4 rtmReprojection(ivec2 texel) {
    return rtmTemporalSlot(texel) == 2 ? rtmViewToPreviousView2 : rtmViewToPreviousView;
}
