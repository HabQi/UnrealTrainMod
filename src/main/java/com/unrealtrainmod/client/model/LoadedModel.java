/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.model;

import com.unrealtrainmod.client.GpuModel;
import com.unrealtrainmod.client.GpuResidency;
import com.unrealtrainmod.client.ModelPreparation;
import com.unrealtrainmod.client.ModelUploads;
import com.unrealtrainmod.client.texture.MaterialTexture;
import com.unrealtrainmod.model.ModelBounds;
import com.unrealtrainmod.model.ModelData;
import com.unrealtrainmod.vehicle.VehicleAction;
import com.unrealtrainmod.vehicle.VehicleDefinition;
import com.unrealtrainmod.compat.ir.IrVehicleDefinition;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.LoggerFactory;

/** Vehicle assembly referencing shared source geometry. */
public record LoadedModel(List<Instance> instances, ModelBounds bounds, IrVehicleDefinition ir) {
    /**
     * Owns GPU geometry; selections only reference its parts and never own rendering resources.
     * {@code bodyParts} maps an action-name set to the body selection that omits those objects.
     * {@code actionSelections} resolves every action node by identity to its ordered object selection.
     */
    public static final class Source implements AutoCloseable {
        private final PreparedModel prepared;
        private GpuModel gpu;
        private boolean uploadFailed;
        private ModelPreparation.Ticket<GpuModel.Prepared> pending;
        private GpuModel.Upload upload;
        private long generation;
        private final List<net.minecraft.resources.ResourceLocation> textures;
        private long texturesFrame = -1;
        private boolean texturesReady;

        public Source(PreparedModel prepared) {
            this.prepared = prepared;
            textures = prepared.batches().stream().map(com.unrealtrainmod.client.renderer.RenderBatch::texture).distinct().toList();
        }
        public List<ModelPart> parts() { return prepared.parts(); }
        public PartSelection visibleParts() { return prepared.visibleParts(); }
        public Map<Set<String>, PartSelection> bodyParts() { return prepared.bodyParts(); }
        public Map<VehicleAction, ActionSelection> actionSelections() { return prepared.actionSelections(); }
        public Map<String, PartSelection> partsByName() { return prepared.partsByName(); }
        public Map<IrVehicleDefinition, IrModelBinding> irBindings() { return prepared.irBindings(); }
        public List<com.unrealtrainmod.client.renderer.RenderBatch> batches() { return prepared.batches(); }

        public boolean texturesReady() {
            if (texturesFrame != ModelUploads.frame()) {
                texturesFrame = ModelUploads.frame(); texturesReady = true;
                for (var texture : textures) texturesReady &= MaterialTexture.ready(texture);
            }
            return texturesReady;
        }

        public boolean nativeReady() {
            gpu();
            return gpu != null || uploadFailed || prepared.batches().isEmpty();
        }

        /** Native world/preview submission requests storage; Iris world rendering never calls this. */
        public GpuModel gpu() {
            if (gpu == null && !uploadFailed && !prepared.batches().isEmpty()) {
                if (pending == null) {
                    pending = ModelPreparation.geometry(() -> GpuModel.prepare(prepared.batches()),
                            GpuModel.Prepared::byteSize, GpuModel.Prepared::close);
                    if (pending != null) {
                        long version = generation;
                        ModelUploads.enqueue(() -> {
                            if (version != generation) return true;
                            if (!pending.ready()) return false;
                            try {
                                if (upload == null) upload = new GpuModel.Upload(pending.value());
                                if (!upload.step()) return false;
                                ModelUploads.commit(() -> {
                                    if (version != generation) return;
                                    gpu = upload.finish(); upload.close(); upload = null;
                                    pending.close(); pending = null;
                                    GpuResidency.uploaded(this, GpuResidency.Kind.NATIVE_GEOMETRY, gpu.byteSize(), this::close);
                                });
                            } catch (RuntimeException exception) {
                                if (upload != null) { upload.close(); upload = null; }
                                pending.close(); pending = null;
                                uploadFailed = true;
                                LoggerFactory.getLogger(Source.class).warn("Cannot upload native vehicle geometry; keeping buffered submission", exception);
                            }
                            return true;
                        });
                    }
                }
            }
            GpuResidency.used(this);
            return gpu;
        }

        @Override public void close() {
            generation++;
            if (upload != null) { upload.close(); upload = null; }
            if (pending != null) { pending.close(); pending = null; }
            GpuResidency.released(this);
            if (gpu != null) { gpu.close(); gpu = null; }
        }
    }
    public record Instance(Source model, VehicleDefinition.Instance definition, PartSelection visibleParts, IrModelBinding irBinding) {
        public ModelData.Position offset() { return definition.offset(); }
    }
}
