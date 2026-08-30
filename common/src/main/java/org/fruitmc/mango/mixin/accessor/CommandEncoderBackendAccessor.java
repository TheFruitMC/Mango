package org.fruitmc.mango.mixin.accessor;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(CommandEncoder.class)
public interface CommandEncoderBackendAccessor {

    @Invoker("backend")
    CommandEncoderBackend mango$invokeBackend();
}
