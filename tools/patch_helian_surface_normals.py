#!/usr/bin/env python3
"""Preserve entity surface normals and materials in the local Helian 1.0.8 adaptation."""

import argparse
from pathlib import Path
import zipfile

from patch_helian_two_cars import replace_once


def patch_textured(source: str) -> str:
    source = replace_once(
        source,
        "    vec2 textureScale;\n"
        "    #if MC_VERSION >= 11300\n"
        "        mat3 tbnMatrix = calcTbnMatrix(texGradX, texGradY, viewPos.xyz, textureScale);\n"
        "    #else\n"
        "        textureScale = calcTextureScale(texGradX, texGradY, viewPos.xyz);\n"
        "    #endif\n\n"
        "    #if MC_VERSION >= 11300 && defined ENTITIES\n"
        "        vec3 utmGeometricNormal = tbnMatrix[2];",
        "    bool noCoord = dot(abs(texGradX) + abs(texGradY), vec2(1.0)) < 1e-6;\n"
        "    vec2 textureScale;\n"
        "    #if MC_VERSION >= 11300 && defined ENTITIES\n"
        "        // Geometry remains independent of UVs, including single-color faces.\n"
        "        vec3 utmGeometricNormal = normalize(cross(dFdx(viewPos), dFdy(viewPos)));\n"
        "        bool utmValidUv = !noCoord && (texGradX.x * texGradY.y - texGradX.y * texGradY.x != 0.0);\n"
        "        textureScale = utmValidUv ? calcTextureScale(texGradX, texGradY, viewPos.xyz) : vec2(1.0);",
    )
    source = replace_once(
        source,
        "        if (dot(utmNormal, utmGeometricNormal) < 0.0) utmNormal = -utmNormal;",
        "        if (!gl_FrontFacing) utmNormal = -utmNormal;",
    )
    source = replace_once(
        source,
        "        if (dot(utmTangent, utmTangent) < 1e-8) {",
        "        if (!utmValidUv || !(dot(utmTangent, utmTangent) > 1e-8)) {",
    )
    source = replace_once(
        source,
        "        tbnMatrix = mat3(utmTangent, cross(utmTangent, utmNormal) * utmHandedness, utmNormal);\n"
        "    #endif",
        "        mat3 tbnMatrix = mat3(utmTangent, cross(utmTangent, utmNormal) * utmHandedness, utmNormal);\n"
        "    #elif MC_VERSION >= 11300\n"
        "        mat3 tbnMatrix = calcTbnMatrix(texGradX, texGradY, viewPos.xyz, textureScale);\n"
        "    #else\n"
        "        textureScale = calcTextureScale(texGradX, texGradY, viewPos.xyz);\n"
        "    #endif",
    )
    source = replace_once(
        source,
        "        bool noCoord = dot(abs(texGradX) + abs(texGradY), vec2(1.0)) < 1e-6;\n",
        "",
    )
    source = replace_once(
        source,
        "        float parallaxScale = ENTITY_TEXTURE_RESOLUTION / max(textureScale.x * albedoTexSize.x, textureScale.y * albedoTexSize.y);",
        "        #if MC_VERSION >= 11300 && defined ENTITIES\n"
        "            if (!utmValidUv || any(lessThanEqual(fixedCoordRange.zw, vec2(0.0)))) {\n"
        "                fixedCoordRange = vec4(0.0, 0.0, 1.0, 1.0);\n"
        "            }\n"
        "        #endif\n"
        "        float parallaxScale = ENTITY_TEXTURE_RESOLUTION / max(textureScale.x * albedoTexSize.x, textureScale.y * albedoTexSize.y);",
    )
    source = replace_once(
        source,
        "        vec3 anisotropicParam = anisotropicOffsetLod(albedoTexSize, albedoTexelSize, texGradX, texGradY, quadSize);",
        "        #if MC_VERSION >= 11300 && defined ENTITIES\n"
        "            vec3 anisotropicParam = utmValidUv\n"
        "                ? anisotropicOffsetLod(albedoTexSize, albedoTexelSize, texGradX, texGradY, quadSize) : vec3(0.0);\n"
        "        #else\n"
        "            vec3 anisotropicParam = anisotropicOffsetLod(albedoTexSize, albedoTexelSize, texGradX, texGradY, quadSize);\n"
        "        #endif",
    )
    source = replace_once(
        source,
        "                    #ifdef VOXEL_PARALLAX\n"
        "                        texcoord = perPixelParallax(",
        "                    #if MC_VERSION >= 11300 && defined ENTITIES\n"
        "                    if (utmValidUv) {\n"
        "                    #endif\n"
        "                    #ifdef VOXEL_PARALLAX\n"
        "                        texcoord = perPixelParallax(",
    )
    source = replace_once(
        source,
        "                    rawData.parallaxOffset = clamp(parallaxOffset * parallaxScale, 0.0, 1.0);",
        "                    rawData.parallaxOffset = clamp(parallaxOffset * parallaxScale, 0.0, 1.0);\n"
        "                    #if MC_VERSION >= 11300 && defined ENTITIES\n"
        "                    }\n"
        "                    #endif",
    )
    source = replace_once(
        source,
        "        if (noCoord) {\n"
        "            // Physics mod snow support",
        "        #if MC_VERSION >= 11300 && defined ENTITIES\n"
        "        if (!utmValidUv) {\n"
        "            // Keep sampled material and the model normal when UVs have no surface basis.\n"
        "            rawData.parallaxOffset = 0.0;\n"
        "        #else\n"
        "        if (noCoord) {\n"
        "            // Physics mod snow support",
    )
    source = replace_once(
        source,
        "        } else {\n"
        "            float wetStrength = 0.0;",
        "        #endif\n"
        "        } else {\n"
        "            float wetStrength = 0.0;",
    )
    source = replace_once(
        source,
        "                rawData.normal = curveNormalTowardView(rawData.normal, rawData.geoNormal, viewDir);",
        "                #if MC_VERSION < 11300 || !defined ENTITIES\n"
        "                    rawData.normal = curveNormalTowardView(rawData.normal, rawData.geoNormal, viewDir);\n"
        "                #endif",
    )
    return source


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Existing Helian-MMCOv1.0.8.zip")
    parser.add_argument("--output", type=Path, default=Path("build/shaderpacks/Helian-MMCOv1.0.9.zip"))
    args = parser.parse_args()
    if args.source.resolve() == args.output.resolve():
        parser.error("Output must differ from the source ZIP")
    config = Path(str(args.source) + ".txt").read_bytes()
    shader = "shaders/programs/gbuffers/Textured.frag"
    with zipfile.ZipFile(args.source) as original:
        changed = patch_textured(original.read(shader).decode("utf-8-sig")).encode("utf-8")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(args.output, "w", compression=zipfile.ZIP_DEFLATED) as output:
            output.comment = original.comment
            for entry in original.infolist():
                output.writestr(entry, changed if entry.filename == shader else original.read(entry))
    Path(str(args.output) + ".txt").write_bytes(config)
    print(f"Created {args.output}; changed {shader}; preserved TXT configuration")


if __name__ == "__main__":
    main()
