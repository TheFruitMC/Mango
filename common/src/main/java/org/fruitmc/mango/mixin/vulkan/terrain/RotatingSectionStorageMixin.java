package org.fruitmc.mango.mixin.vulkan.terrain;

import net.minecraft.client.RotatingSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Iterator;
import java.util.NoSuchElementException;

@Mixin(RotatingSectionStorage.class)
public abstract class RotatingSectionStorageMixin<T extends RotatingSectionStorage.Value> {

    @Inject(
        method = "iterator()Ljava/util/Iterator;",
        at = @At("RETURN"),
        cancellable = true,
        require = 1
    )
    private void mango$fixIteratorSkip(CallbackInfoReturnable<Iterator<T>> cir) {
        RotatingSectionStorage<T> self = (RotatingSectionStorage<T>)(Object)this;
        Iterator<T> delegate = cir.getReturnValue();
        cir.setReturnValue(new Iterator<>() {
            private int remaining = self.size();

            @Override
            public boolean hasNext() {
                return this.remaining > 0;
            }

            @Override
            public T next() {
                if (this.remaining <= 0) {
                    throw new NoSuchElementException();
                }
                this.remaining--;
                return delegate.next();
            }
        });
    }
}
