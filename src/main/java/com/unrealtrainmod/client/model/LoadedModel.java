/*
 * Copyright (c) 2026 HabQi
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.unrealtrainmod.client.model;

import com.unrealtrainmod.client.GpuModel;
import com.unrealtrainmod.client.GpuResidency;
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

        public Source(PreparedModel prepared) { this.prepared = prepared; }
        public List<ModelPart> parts() { return prepared.parts(); }
        public PartSelection visibleParts() { return prepared.visibleParts(); }
        public Map<Set<String>, PartSelection> bodyParts() { return prepared.bodyParts(); }
        public Map<VehicleAction, ActionSelection> actionSelections() { return prepared.actionSelections(); }
        public Map<String, PartSelection> partsByName() { return prepared.partsByName(); }
        public Map<IrVehicleDefinition, IrModelBinding> irBindings() { return prepared.irBindings(); }

        /** Native world/preview submission requests storage; Iris world rendering never calls this. */
        public GpuModel gpu() {
            if (gpu == null && !uploadFailed && !prepared.batches().isEmpty()) {
                try {
                    gpu = GpuModel.upload(prepared.batches());
                    GpuResidency.uploaded(this, GpuResidency.Kind.NATIVE_GEOMETRY, gpu.byteSize(), this::close);
                } catch (RuntimeException exception) {
                    uploadFailed = true;
                    LoggerFactory.getLogger(Source.class).warn("Cannot upload native vehicle geometry; keeping buffered submission", exception);
                }
            }
            GpuResidency.used(this);
            return gpu;
        }

        @Override public void close() {
            GpuResidency.released(this);
            if (gpu != null) { gpu.close(); gpu = null; }
        }
    }
    public record Instance(Source model, VehicleDefinition.Instance definition, PartSelection visibleParts, IrModelBinding irBinding) {
        public ModelData.Position offset() { return definition.offset(); }
    }
}
