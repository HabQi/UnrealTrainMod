#!/usr/bin/env python3
"""Extend the local Helian 1.0.7 adaptation with UTM's two-car motion protocol."""

import argparse
from pathlib import Path
import zipfile


def replace_once(source: str, old: str, new: str) -> str:
    if source.count(old) != 1:
        raise ValueError(f"Expected one shader anchor: {old!r}")
    return source.replace(old, new, 1)


def patch_gbuffer(source: str) -> str:
    source = replace_once(
        source,
        "uint pack2x16Bit(vec2 x) {",
        '#include "/libs/VehicleReprojection.glsl"\n\nuint pack2x16Bit(vec2 x) {',
    )
    return replace_once(
        source,
        "    data.parallaxOffset = unpacked2a.y;",
        "    data.parallaxOffset = data.materialID == MAT_RTM\n"
        "        ? unpackRtmParallax(unpacked2a.y) : unpacked2a.y;",
    )


def patch_textured(source: str) -> str:
    return replace_once(
        source,
        "    packUpGbufferDataSolid(rawData, gbufferData0, gbufferData1, gbufferData2);",
        "    #ifdef ENTITIES\n"
        "        if (rawData.materialID == MAT_RTM) {\n"
        "            rawData.parallaxOffset = packRtmParallax(rawData.parallaxOffset, rtmTemporalEntity);\n"
        "        }\n"
        "    #endif\n\n"
        "    packUpGbufferDataSolid(rawData, gbufferData0, gbufferData1, gbufferData2);",
    )


def patch_edges(source: str) -> str:
    return replace_once(
        source,
        "            materialEdge = max(materialEdge, step(0.5, abs(sampleMaterial - centerMaterial)));",
        "            materialEdge = max(materialEdge, step(0.5, abs(sampleMaterial - centerMaterial)));\n"
        "            if (centerMaterial == MAT_RTM && sampleMaterial == MAT_RTM) {\n"
        "                materialEdge = max(materialEdge, float(rtmTemporalSlot(sampleTexel) != rtmTemporalSlot(texel)));\n"
        "            }",
    )


def patch_deferred(source: str) -> str:
    source = replace_once(source, "uniform mat4 rtmViewToPreviousView;\n", "")
    source = replace_once(
        source,
        "        bool isRtmHistory = gbufferData.materialID == MAT_RTM;",
        "        bool isRtmHistory = gbufferData.materialID == MAT_RTM;\n"
        "        mat4 rtmMotion = mat4(1.0);\n"
        "        if (isRtmHistory) rtmMotion = rtmReprojection(texel);",
    )
    source = replace_once(
        source,
        "            prevViewPos = (rtmViewToPreviousView * vec4(viewPos, 1.0)).xyz;",
        "            prevViewPos = (rtmMotion * vec4(viewPos, 1.0)).xyz;",
    )
    source = replace_once(
        source,
        "            prevViewGeoNormal = normalize(mat3(rtmViewToPreviousView) * gbufferData.geoNormal);",
        "            prevViewGeoNormal = normalize(mat3(rtmMotion) * gbufferData.geoNormal);",
    )
    return patch_edges(source)


def patch_composite(source: str) -> str:
    source = replace_once(source, "uniform mat4 rtmViewToPreviousView;\n", "")
    source = replace_once(
        source, "vec3 calculateRtmVelocity(vec3 coord) {", "vec3 calculateRtmVelocity(vec3 coord, ivec2 texel) {"
    )
    source = replace_once(
        source,
        "    vec3 prevView = (rtmViewToPreviousView * vec4(view, 1.0)).xyz;",
        "    vec3 prevView = (rtmReprojection(texel) * vec4(view, 1.0)).xyz;",
    )
    source = replace_once(
        source, "        velocity = calculateRtmVelocity(motionCoord);", "        velocity = calculateRtmVelocity(motionCoord, texel);"
    )
    return patch_edges(source)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Existing Helian-MMCOv1.0.7.zip")
    parser.add_argument("--output", type=Path, default=Path("build/shaderpacks/Helian-MMCOv1.0.8.zip"))
    args = parser.parse_args()
    if args.source.resolve() == args.output.resolve():
        parser.error("Output must differ from the source ZIP")
    config = Path(str(args.source) + ".txt").read_bytes()
    patches = {
        "shaders/libs/GbufferData.glsl": patch_gbuffer,
        "shaders/programs/gbuffers/Textured.frag": patch_textured,
        "shaders/programs/deferred/Deferred0.frag": patch_deferred,
        "shaders/programs/composite/Composite7.frag": patch_composite,
    }
    helper_name = "shaders/libs/VehicleReprojection.glsl"
    helper = (Path(__file__).parent / "helian/VehicleReprojection.glsl").read_bytes()
    with zipfile.ZipFile(args.source) as original:
        if helper_name in original.namelist():
            raise ValueError("The source already contains the two-car patch")
        # Validate all source anchors before creating the output archive.
        changed = {
            name: patch(original.read(name).decode("utf-8-sig")).encode("utf-8")
            for name, patch in patches.items()
        }
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(args.output, "w", compression=zipfile.ZIP_DEFLATED) as output:
            output.comment = original.comment
            for entry in original.infolist():
                output.writestr(entry, changed.get(entry.filename, original.read(entry)))
            output.writestr(helper_name, helper)
    Path(str(args.output) + ".txt").write_bytes(config)
    print(f"Created {args.output}; changed {len(changed)} shaders, added {helper_name}; preserved TXT configuration")


if __name__ == "__main__":
    main()
