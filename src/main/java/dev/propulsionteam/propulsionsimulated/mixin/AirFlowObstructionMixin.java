package dev.propulsionteam.propulsionsimulated.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.AllTags;
import com.simibubi.create.content.decoration.copycat.CopycatBlock;
import com.simibubi.create.content.kinetics.fan.AirCurrent;
import com.simibubi.create.content.kinetics.fan.IAirCurrentSource;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AirCurrent.class)
public abstract class AirFlowObstructionMixin {

    private static final double[][] DEPTH_TEST_COORDINATES = {
            {0.25, 0.25},
            {0.25, 0.75},
            {0.5, 0.5},
            {0.75, 0.25},
            {0.75, 0.75}
    };

    @Shadow
    @Final
    public IAirCurrentSource source;

    @WrapOperation(
            method = "rebuild",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/simibubi/create/content/kinetics/fan/AirCurrent;getFlowLimit(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;FLnet/minecraft/core/Direction;)F"
            )
    )
    private float propulsion$robustFlowLimit(
            Level world,
            BlockPos start,
            float max,
            Direction facing,
            Operation<Float> original
    ) {
        SubLevel subLevel = Sable.HELPER.getContaining(world, start);
        if (subLevel != null) {
            return propulsion$flowLimitSubLevel(world, start, max, facing);
        }
        return propulsion$flowLimitRobust(world, start, max, facing);
    }

    private static float propulsion$flowLimitSubLevel(Level world, BlockPos start, float max, Direction facing) {
        Vec3 flow = Vec3.atLowerCornerOf(facing.getNormal());
        Vec3 rayStart = Vec3.atCenterOf(start).add(flow.scale(0.501));
        Vec3 rayEnd = rayStart.add(flow.scale(max + 0.5));

        BlockHitResult hit = world.clip(new ClipContext(
                rayStart,
                rayEnd,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                CollisionContext.empty()
        ));

        if (hit.getType() != HitResult.Type.BLOCK) {
            return max;
        }

        float limit = (float) (hit.getLocation().distanceTo(rayStart) - 0.5);
        return Mth.clamp(limit, 0.0f, max);
    }

    private static float propulsion$flowLimitRobust(Level world, BlockPos start, float max, Direction facing) {
        for (int i = 0; i < max; i++) {
            BlockPos currentPos = start.relative(facing, i + 1);
            if (!world.isLoaded(currentPos)) {
                return i;
            }

            BlockState state = world.getBlockState(currentPos);
            BlockState copycatState = CopycatBlock.getMaterial(world, currentPos);
            if (propulsion$shouldAlwaysPass(copycatState.isAir() ? state : copycatState)) {
                continue;
            }

            VoxelShape shape = state.getCollisionShape(world, currentPos);
            if (shape.isEmpty()) {
                continue;
            }
            if (shape == Shapes.block()) {
                return i;
            }
            if (Block.isFaceFull(shape, facing.getOpposite())) {
                return i;
            }

            double shapeDepth = propulsion$findMaxDepth(shape, facing);
            if (shapeDepth == Double.POSITIVE_INFINITY) {
                return i;
            }
            return Math.min((float) (i + shapeDepth + 1 / 32d), max);
        }
        return max;
    }

    private static boolean propulsion$shouldAlwaysPass(BlockState state) {
        return AllTags.AllBlockTags.FAN_TRANSPARENT.matches(state);
    }

    private static double propulsion$findMaxDepth(VoxelShape shape, Direction direction) {
        Direction.Axis axis = direction.getAxis();
        Direction.AxisDirection axisDirection = direction.getAxisDirection();
        double maxDepth = 0;

        for (double[] coordinates : DEPTH_TEST_COORDINATES) {
            double depth;
            if (axisDirection == Direction.AxisDirection.POSITIVE) {
                double min = shape.min(axis, coordinates[0], coordinates[1]);
                if (min == Double.POSITIVE_INFINITY) {
                    return Double.POSITIVE_INFINITY;
                }
                depth = min;
            } else {
                double max = shape.max(axis, coordinates[0], coordinates[1]);
                if (max == Double.NEGATIVE_INFINITY) {
                    return Double.POSITIVE_INFINITY;
                }
                depth = 1 - max;
            }

            if (depth > maxDepth) {
                maxDepth = depth;
            }
        }

        return maxDepth;
    }
}
