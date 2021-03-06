package burn447.dartcraftReloaded.tileEntity;

import burn447.dartcraftReloaded.Energy.DCREnergyStorage;
import burn447.dartcraftReloaded.Fluids.FluidForce;
import burn447.dartcraftReloaded.Items.ItemArmor;
import burn447.dartcraftReloaded.Items.ModItems;
import burn447.dartcraftReloaded.Items.Tools.*;
import burn447.dartcraftReloaded.blocks.ModBlocks;
import burn447.dartcraftReloaded.blocks.torch.BlockForceTorch;
import burn447.dartcraftReloaded.util.DartUtils;
import burn447.dartcraftReloaded.util.References;
import net.minecraft.block.Block;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Enchantments;
import net.minecraft.init.Items;
import net.minecraft.init.MobEffects;
import net.minecraft.inventory.ItemStackHelper;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemTool;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.potion.PotionEffect;
import net.minecraft.potion.PotionUtils;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.NonNullList;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidTankProperties;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.ItemStackHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

import static burn447.dartcraftReloaded.Handlers.DCRCapabilityHandler.CAPABILITY_FORCEROD;
import static burn447.dartcraftReloaded.Handlers.DCRCapabilityHandler.CAPABILITY_TOOLMOD;
import static net.minecraftforge.fluids.capability.CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY;


/**
 * Created by BURN447 on 3/30/2018.
 */
public class TileEntityInfuser extends TileEntity implements ITickable, ICapabilityProvider, ITileEntityProvider, IFluidHandler {


    public final ItemStackHandler handler;
    public FluidTank tank;

    private NonNullList<ItemStack> infuserContents = NonNullList.create();

    public static List<Item> validToolList = new ArrayList<>();
    public static List<Item> validModifierList = new ArrayList<>();
    public boolean canWork = false;

    public int processTime = 0;
    public int maxProcessTime = 17;

    public static int MAX_POWER = 1000000;
    public static int RF_PER_TICK = 20;
    public static int PR_PER_TICK_INPUT = 200;

    public int fluidContained;
    public int energyStored;

    public DCREnergyStorage energyStorage = new DCREnergyStorage(MAX_POWER, 1000);

    public TileEntityInfuser() {
        populateToolList();
        populateModiferList();
        this.handler = new ItemStackHandler(11) {
            @Override
            protected int getStackLimit(int slot, ItemStack stack) {
                return 1;
            }
        };

        tank = new FluidTank(50000);
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        //Items
        handler.deserializeNBT(nbt.getCompoundTag("ItemStackHandler"));
        ItemStackHelper.loadAllItems(nbt, this.infuserContents);
        energyStorage.setEnergy(nbt.getInteger("EnergyHandler"));
        tank.readFromNBT(nbt);

        super.markDirty();
        super.readFromNBT(nbt);
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        //Items
        nbt.setTag("ItemStackHandler", handler.serializeNBT());
        nbt.setInteger("EnergyHandler", energyStorage.getEnergyStored());
        ItemStackHelper.saveAllItems(nbt, this.infuserContents);
        tank.writeToNBT(nbt);

        return super.writeToNBT(nbt);
    }


    @Override
    public void update() {
        fluidContained = tank.getFluidAmount();
        if (world != null) {
            if (!world.isRemote) {
                processForceGems();
                if (canWork) {
                    if (processTime == maxProcessTime) {
                        this.markDirty();
                        processTool();
                    }
                    processTime++;
                }
            }
        }
    }

    //Processes force Gems in the force infuser slot
    private void processForceGems() {
        if (handler.getStackInSlot(9).getItem() == ModItems.gemForceGem) {
            FluidStack force = new FluidStack(FluidRegistry.getFluid("force"), 500);

            if (tank.getFluidAmount() < tank.getCapacity() - 100) {
                fill(force, true);
                if (handler.getStackInSlot(9).getCount() > 1) {
                    handler.getStackInSlot(9).setCount(handler.getStackInSlot(9).getCount() - 1);
                } else
                    handler.setStackInSlot(9, ItemStack.EMPTY);

                markDirty();
                world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 2);
            }
        }
    }

    private void processTool() {
        if (hasValidTool()) {
            for (int i = 0; i < 8; i++) {
                if (hasValidModifer(i)) {
                    ItemStack mod = getModifier(i);
                    ItemStack stack = handler.getStackInSlot(8);
                    boolean success = applyModifier(stack, mod);
                    if (success) {
                        handler.setStackInSlot(i, ItemStack.EMPTY);
                        tank.drain(1000, true);
                        energyStorage.consumePower(RF_PER_TICK);
                        world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 2);
                    }
                }
            }
        }
        canWork = false;
        processTime = 0;
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        NBTTagCompound nbt = new NBTTagCompound();
        markDirty();
        writeToNBT(nbt);
        int metadata = getBlockMetadata();
        return new SPacketUpdateTileEntity(this.pos, metadata, nbt);
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        this.readFromNBT(pkt.getNbtCompound());
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        NBTTagCompound nbt = new NBTTagCompound();
        markDirty();
        this.writeToNBT(nbt);
        return nbt;
    }

    @Override
    public void handleUpdateTag(NBTTagCompound tag) {
        this.readFromNBT(tag);
    }

    @Override
    public NBTTagCompound getTileData() {
        NBTTagCompound nbt = new NBTTagCompound();
        this.writeToNBT(nbt);
        return nbt;
    }

    @Override
    public <T> T getCapability(Capability<T> capability, EnumFacing facing) {
        if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY)
            return (T) this.handler;
        if (capability == FLUID_HANDLER_CAPABILITY)
            return (T) this.tank;
        if (capability == CapabilityEnergy.ENERGY)
            return CapabilityEnergy.ENERGY.cast(energyStorage);

        return super.getCapability(capability, facing);
    }

    @Override
    public boolean hasCapability(Capability<?> capability, EnumFacing facing) {
        if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY || capability == FLUID_HANDLER_CAPABILITY || capability == CapabilityEnergy.ENERGY)
            return true;
        return super.hasCapability(capability, facing);
    }

    private boolean hasValidTool() {
        if (!handler.getStackInSlot(8).isEmpty()) {
            for (int i = 0; i < References.numTools; i++) {
                if (handler.getStackInSlot(8).getItem() == validToolList.get(i)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasValidModifer(int slot) {
        if (!handler.getStackInSlot(8).isEmpty()) {
            for (int j = 0; j < References.numModifiers - 1; j++) {
                if (handler.getStackInSlot(slot).getItem() == validModifierList.get(j)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void populateToolList() {
        validToolList.add(ModItems.forcePickaxe);
        validToolList.add(ModItems.forceAxe);
        validToolList.add(ModItems.forceShovel);
        validToolList.add(ModItems.forceSword);
        validToolList.add(ModItems.forceRod);
        validToolList.add(ModItems.forceShears);
        validToolList.add(ModItems.forceHelmet);
        validToolList.add(ModItems.forceChest);
        validToolList.add(ModItems.forceLegs);
        validToolList.add(ModItems.forceBoots);
        validToolList.add(Item.getItemFromBlock(ModBlocks.forceTorch));
    }

    private void populateModiferList() {
        validModifierList.add(ModItems.nuggetForce);
        validModifierList.add(ModItems.claw);
        validModifierList.add(ModItems.fortune);
        validModifierList.add(Items.SUGAR);
        validModifierList.add(Items.COAL);
        validModifierList.add(ModItems.goldenPowerSource);
        validModifierList.add(ModItems.cookieFortune);
        validModifierList.add(Items.FLINT);
        validModifierList.add(Items.DYE);
        validModifierList.add(Items.EXPERIENCE_BOTTLE);
        validModifierList.add(Items.SPIDER_EYE);
        validModifierList.add(Items.ARROW);
        validModifierList.add(Items.GHAST_TEAR);
        validModifierList.add(ModItems.soulWafer);
        validModifierList.add(Items.FEATHER);
        validModifierList.add(Items.ENDER_PEARL);
        validModifierList.add(Items.GLOWSTONE_DUST);
        validModifierList.add(Items.POTIONITEM);
        validModifierList.add(Items.CLOCK);
        validModifierList.add(Item.getItemFromBlock(Blocks.CRAFTING_TABLE));
        validModifierList.add(Item.getItemFromBlock(ModBlocks.forceLog));
        validModifierList.add(Item.getItemFromBlock(Blocks.WEB));
        validModifierList.add(Item.getItemFromBlock(Blocks.OBSIDIAN));
        validModifierList.add(Item.getItemFromBlock(Blocks.BRICK_BLOCK));
    }

    private ItemStack getModifier(int slot) {
        if (!handler.getStackInSlot(8).isEmpty()) {
            for (int j = 0; j < References.numModifiers - 1; j++) {
                if (handler.getStackInSlot(slot).getItem() == validModifierList.get(j)) {
                    return handler.getStackInSlot(slot);
                }
            }
        }
        return null;
    }

    @Nullable
    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta) {
        return new TileEntityInfuser();
    }

    private boolean applyModifier(ItemStack stack, ItemStack mod) {
        Item modifier = mod.getItem();
        if (modifier == Items.SUGAR)
            return addSpeedModifier(stack);
        if (modifier == Items.COAL)
            return addHeatModifier(stack);
        if (modifier == Items.FLINT)
            return addGrindingModifier(stack);
        if (modifier == ModItems.nuggetForce)
            return addForceModifier(stack);
        if (modifier == Item.getItemFromBlock(Blocks.WEB))
            return addSilkTouchModifier(stack);
        if (modifier == ModItems.claw)
            return addDamageModifier(stack);
        if (modifier == ModItems.fortune)
            return addLuckModifier(stack);
        if (modifier == Items.GLOWSTONE_DUST)
            return addLightModifier(stack);
        if (modifier == Item.getItemFromBlock(Blocks.BRICK_BLOCK) || modifier == Item.getItemFromBlock(Blocks.OBSIDIAN))
            return addSturdyModifier(stack);
        if (modifier == Item.getItemFromBlock(ModBlocks.forceLog))
            return addLumberjackModifier(stack);
        if (modifier == Items.GHAST_TEAR)
            return addHealingModifier(stack);
        if (modifier == Items.ENDER_PEARL)
            return addEnderModifier(stack);
        if (modifier == Items.ARROW)
            return addBleedingModifier(stack);
        if (modifier == Items.SPIDER_EYE)
            return addBaneModifier(stack);
        if (modifier == Items.FEATHER)
            return addWingModifier(stack);
        if (modifier == Items.POTIONITEM) {
            List<PotionEffect> effects = PotionUtils.getEffectsFromStack(mod);
            for (PotionEffect e : effects) {
                if (e.getPotion() == MobEffects.NIGHT_VISION) {
                    return addSightModifier(stack);
                }
                if (e.getPotion() == MobEffects.INVISIBILITY) {
                    return addCamoModifier(stack);
                }
            }
        }
        if (modifier == Items.DYE) {
            if (mod.getMetadata() == 4) {
                return addRainbowModifier(stack);
            }
        }
        if(modifier == Items.CLOCK)
            return addTimeModifier(stack);

        return false;
    }

    private boolean addSpeedModifier(ItemStack stack) {
        if (stack.getItem() instanceof ItemTool) {
            if (stack.getItem() instanceof ItemForcePickaxe || stack.getItem() instanceof ItemForceShovel || stack.getItem() instanceof ItemForceAxe) {
                if (stack.hasCapability(CAPABILITY_TOOLMOD, null)) {
                    if (stack.getCapability(CAPABILITY_TOOLMOD, null).getEfficiency() == 8.0) {
                        stack.getCapability(CAPABILITY_TOOLMOD, null).setEfficiency(12.0F);
                        return true;
                    } else if (stack.getCapability(CAPABILITY_TOOLMOD, null).getEfficiency() == 12.0) {
                        stack.getCapability(CAPABILITY_TOOLMOD, null).setEfficiency(16.0F);
                        return true;
                    }
                }
            }
            return false;
        }
        if (stack.getItem() instanceof ItemArmor) {
            if (stack.hasCapability(CAPABILITY_TOOLMOD, null)) {
                if (stack.getCapability(CAPABILITY_TOOLMOD, null).hasSpeed())
                    return false;
                else {
                    stack.getCapability(CAPABILITY_TOOLMOD, null).setSpeed(true);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean addHeatModifier(ItemStack stack) {
        if (stack.getItem() instanceof ItemTool || stack.getItem() instanceof ItemArmor) {
            if (stack.hasCapability(CAPABILITY_TOOLMOD, null)) {
                stack.getCapability(CAPABILITY_TOOLMOD, null).setHeat(true);
                return true;
            }
        }
        return false;
    }

    private boolean addForceModifier(ItemStack stack) {
        if (stack.getItem() instanceof ItemForceSword) {
            if (stack.hasCapability(CAPABILITY_TOOLMOD, null)) {
                if (stack.getCapability(CAPABILITY_TOOLMOD, null).getKnockback() == 1.5F)
                    stack.getCapability(CAPABILITY_TOOLMOD, null).setKnockback(1.5F);
                else if (stack.getCapability(CAPABILITY_TOOLMOD, null).getKnockback() == 1.5F)
                    stack.getCapability(CAPABILITY_TOOLMOD, null).setKnockback(2.0F);
                return true;
            }
        }
        return false;
    }

    private boolean addGrindingModifier(ItemStack stack) {
        if (stack.getItem() instanceof ItemForceAxe || stack.getItem() instanceof ItemForceShovel || stack.getItem() instanceof ItemForcePickaxe) {
            if (stack.hasCapability(CAPABILITY_TOOLMOD, null)) {
                stack.getCapability(CAPABILITY_TOOLMOD, null).setGrinding(true);
                return true;
            }
        }
        return false;
    }

    private boolean addSilkTouchModifier(ItemStack stack) {
        if (stack.getItem() instanceof ItemForceAxe || stack.getItem() instanceof ItemForceShovel || stack.getItem() instanceof ItemForcePickaxe) {
            stack.addEnchantment(Enchantments.SILK_TOUCH, 1);
        }
        return false;
    }

    private boolean addDamageModifier(ItemStack stack) {
        if (stack.getItem() instanceof ItemForceSword) {
            if (stack.getCapability(CAPABILITY_TOOLMOD, null).getAttackDamage() == 8.0F) {
                stack.getCapability(CAPABILITY_TOOLMOD, null).setAttackDamage(12.0F);
                return true;
            } else if (stack.getCapability(CAPABILITY_TOOLMOD, null).getAttackDamage() == 12.0F) {
                stack.getCapability(CAPABILITY_TOOLMOD, null).setAttackDamage(16.0F);
                return true;
            }
        } else if (stack.getItem() instanceof ItemArmor) {
            if (!stack.getCapability(CAPABILITY_TOOLMOD, null).hasDamage()) {
                stack.getCapability(CAPABILITY_TOOLMOD, null).setDamage(true);
                return true;
            }
        }
        return false;
    }

    private boolean addLuckModifier(ItemStack stack) {
        if (stack.getItem() instanceof ItemForcePickaxe || stack.getItem() instanceof ItemForceShovel || stack.getItem() instanceof ItemForceAxe) {
            if (!stack.getCapability(CAPABILITY_TOOLMOD, null).hasLuckLevel(1) && !stack.getCapability(CAPABILITY_TOOLMOD, null).hasLuckLevel(4)) {
                stack.addEnchantment(Enchantments.FORTUNE, 1);
                stack.getCapability(CAPABILITY_TOOLMOD, null).setLuck(1);
                return true;
            } else if (!stack.getCapability(CAPABILITY_TOOLMOD, null).hasLuckLevel(2)) {
                DartUtils.removeEnchant(Enchantments.FORTUNE, stack);
                stack.addEnchantment(Enchantments.FORTUNE, 2);
                stack.getCapability(CAPABILITY_TOOLMOD, null).setLuck(2);
                return true;
            } else if (!stack.getCapability(CAPABILITY_TOOLMOD, null).hasLuckLevel(3)) {
                DartUtils.removeEnchant(Enchantments.FORTUNE, stack);
                stack.addEnchantment(Enchantments.FORTUNE, 3);
                stack.getCapability(CAPABILITY_TOOLMOD, null).setLuck(3);
                return true;
            } else if (!stack.getCapability(CAPABILITY_TOOLMOD, null).hasLuckLevel(4)) {
                DartUtils.removeEnchant(Enchantments.FORTUNE, stack);
                stack.addEnchantment(Enchantments.FORTUNE, 4);
                stack.getCapability(CAPABILITY_TOOLMOD, null).setLuck(4);
                return true;
            }
        } else if (stack.getItem() instanceof ItemForceSword) {
            if (!stack.getCapability(CAPABILITY_TOOLMOD, null).hasLuckLevel(1) && !stack.getCapability(CAPABILITY_TOOLMOD, null).hasLuckLevel(4)) {
                stack.addEnchantment(Enchantments.LOOTING, 1);
                stack.getCapability(CAPABILITY_TOOLMOD, null).setLuck(1);
                return true;
            } else if (!stack.getCapability(CAPABILITY_TOOLMOD, null).hasLuckLevel(2)) {
                DartUtils.removeEnchant(Enchantments.LOOTING, stack);
                stack.addEnchantment(Enchantments.LOOTING, 2);
                stack.getCapability(CAPABILITY_TOOLMOD, null).setLuck(2);
                return true;
            } else if (!stack.getCapability(CAPABILITY_TOOLMOD, null).hasLuckLevel(3)) {
                DartUtils.removeEnchant(Enchantments.LOOTING, stack);
                stack.addEnchantment(Enchantments.LOOTING, 3);
                stack.getCapability(CAPABILITY_TOOLMOD, null).setLuck(3);
                return true;
            } else if (!stack.getCapability(CAPABILITY_TOOLMOD, null).hasLuckLevel(4)) {
                DartUtils.removeEnchant(Enchantments.LOOTING, stack);
                stack.addEnchantment(Enchantments.LOOTING, 4);
                stack.getCapability(CAPABILITY_TOOLMOD, null).setLuck(4);
                return true;
            }
        } else if (stack.getItem() instanceof ItemArmor) {
            if (stack.getCapability(CAPABILITY_TOOLMOD, null).hasLuckLevel(1))
                return false;
            else if (!stack.getCapability(CAPABILITY_TOOLMOD, null).hasLuckLevel(1)) {
                stack.getCapability(CAPABILITY_TOOLMOD, null).setLuck(1);
            }
        }
        return false;
    }

    private boolean addLightModifier(ItemStack stack) {
        //Smite
        if (stack.getItem() instanceof ItemForceSword) {
            if (!stack.getCapability(CAPABILITY_TOOLMOD, null).hasLightLevel(1)) {
                stack.addEnchantment(Enchantments.SMITE, 1);
                stack.getCapability(CAPABILITY_TOOLMOD, null).setLight(1);
                return true;
            } else if (!stack.getCapability(CAPABILITY_TOOLMOD, null).hasLightLevel(2)) {
                DartUtils.removeEnchant(Enchantments.SMITE, stack);
                stack.addEnchantment(Enchantments.SMITE, 2);
                stack.getCapability(CAPABILITY_TOOLMOD, null).setLight(2);
                return true;
            } else if (!stack.getCapability(CAPABILITY_TOOLMOD, null).hasLightLevel(3)) {
                DartUtils.removeEnchant(Enchantments.SMITE, stack);
                stack.addEnchantment(Enchantments.SMITE, 3);
                stack.getCapability(CAPABILITY_TOOLMOD, null).setLight(3);
                return true;
            } else if (!stack.getCapability(CAPABILITY_TOOLMOD, null).hasLightLevel(4)) {
                DartUtils.removeEnchant(Enchantments.SMITE, stack);
                stack.addEnchantment(Enchantments.SMITE, 4);
                stack.getCapability(CAPABILITY_TOOLMOD, null).setLight(4);
                return true;
            } else if (!stack.getCapability(CAPABILITY_TOOLMOD, null).hasLightLevel(5)) {
                DartUtils.removeEnchant(Enchantments.SMITE, stack);
                stack.addEnchantment(Enchantments.SMITE, 5);
                stack.getCapability(CAPABILITY_TOOLMOD, null).setLight(5);
                return true;
            }
        }
        return false;
    }

    private boolean addSturdyModifier(ItemStack stack) {
        if (stack.getItem() instanceof ItemTool) {
            if (!stack.getCapability(CAPABILITY_TOOLMOD, null).hasSturdyLevel(1)) {
                stack.addEnchantment(Enchantments.UNBREAKING, 1);
                stack.getCapability(CAPABILITY_TOOLMOD, null).setSturdy(1);
                return true;
            } else if (!stack.getCapability(CAPABILITY_TOOLMOD, null).hasSturdyLevel(2)) {
                DartUtils.removeEnchant(Enchantments.UNBREAKING, stack);
                stack.addEnchantment(Enchantments.UNBREAKING, 2);
                stack.getCapability(CAPABILITY_TOOLMOD, null).setSturdy(2);
                return true;
            } else if (!stack.getCapability(CAPABILITY_TOOLMOD, null).hasSturdyLevel(3)) {
                DartUtils.removeEnchant(Enchantments.UNBREAKING, stack);
                stack.addEnchantment(Enchantments.UNBREAKING, 3);
                stack.getCapability(CAPABILITY_TOOLMOD, null).setSturdy(3);
                return true;
            }
        }
        return false;
    }

    private boolean addLumberjackModifier(ItemStack stack) {
        if (stack.getItem() instanceof ItemForceAxe) {
            if (!stack.getCapability(CAPABILITY_TOOLMOD, null).hasLumberJack()) {
                stack.getCapability(CAPABILITY_TOOLMOD, null).setLumberJack(true);
                return true;
            }
        }
        return false;
    }

    private boolean addHealingModifier(ItemStack stack) {
        if (stack.getItem() instanceof ItemForceRod) {
            if (!stack.getCapability(CAPABILITY_FORCEROD, null).isRodOfHealing(1)) {
                stack.getCapability(CAPABILITY_FORCEROD, null).setRodOfHealing(true, 1);
                return true;
            } else if (!stack.getCapability(CAPABILITY_FORCEROD, null).isRodOfHealing(2) && stack.getCapability(CAPABILITY_FORCEROD, null).isRodOfHealing(1)) {
                stack.getCapability(CAPABILITY_FORCEROD, null).setRodOfHealing(true, 2);
                return true;
            } else if (!stack.getCapability(CAPABILITY_FORCEROD, null).isRodOfHealing(3) && stack.getCapability(CAPABILITY_FORCEROD, null).isRodOfHealing(2) && stack.getCapability(CAPABILITY_FORCEROD, null).isRodOfHealing(1)) {
                stack.getCapability(CAPABILITY_FORCEROD, null).setRodOfHealing(true, 3);
                return true;
            }
        }
        return false;
    }

    private boolean addCamoModifier(ItemStack stack) {
        if (stack.getItem() instanceof ItemForceRod) {
            stack.getCapability(CAPABILITY_FORCEROD, null).setCamoModifier(true);
            return true;
        } else if (stack.getItem() instanceof ItemArmor) {
            stack.getCapability(CAPABILITY_TOOLMOD, null).setCamo(true);
            return true;
        }
        return false;
    }

    private boolean addEnderModifier(ItemStack stack) {
        if (stack.getItem() instanceof ItemForceRod) {
            stack.getCapability(CAPABILITY_FORCEROD, null).setEnderModifier(true);
            return true;
        } else if (stack.getItem() instanceof ItemForceSword) {
            stack.getCapability(CAPABILITY_TOOLMOD, null).setEnder(true);
            return true;
        }
        return false;
    }

    private boolean addSightModifier(ItemStack stack) {
        if (stack.getItem() instanceof ItemForceRod) {
            stack.getCapability(CAPABILITY_FORCEROD, null).setSightModifier(true);
            return true;
        }
        return false;
    }

    private boolean addRainbowModifier(ItemStack stack) {
        if (stack.getItem() instanceof ItemForceShears) {
            stack.getCapability(CAPABILITY_TOOLMOD, null).setRainbow(true);
            return true;
        }
        return false;
    }

    private boolean addBleedingModifier(ItemStack stack) {
        if (stack.getItem() instanceof ItemForceSword || stack.getItem() instanceof ItemArmor) {
            if (!stack.getCapability(CAPABILITY_TOOLMOD, null).hasBleeding(1)) {
                stack.getCapability(CAPABILITY_TOOLMOD, null).setBleeding(1);
                return true;
            } else if (stack.getCapability(CAPABILITY_TOOLMOD, null).hasBleeding(1) && !stack.getCapability(CAPABILITY_TOOLMOD, null).hasBleeding(2)) {
                stack.getCapability(CAPABILITY_TOOLMOD, null).setBleeding(2);
                return true;
            } else if (stack.getCapability(CAPABILITY_TOOLMOD, null).hasBleeding(2) && stack.getCapability(CAPABILITY_TOOLMOD, null).hasBleeding(1) && !stack.getCapability(CAPABILITY_TOOLMOD, null).hasBleeding(3)) {
                stack.getCapability(CAPABILITY_TOOLMOD, null).setBleeding(3);
                return true;
            } else if (stack.getCapability(CAPABILITY_TOOLMOD, null).hasBleeding(3) && stack.getCapability(CAPABILITY_TOOLMOD, null).hasBleeding(2) && stack.getCapability(CAPABILITY_TOOLMOD, null).hasBleeding(1) && !stack.getCapability(CAPABILITY_TOOLMOD, null).hasBleeding(4)) {
                stack.getCapability(CAPABILITY_TOOLMOD, null).setBleeding(4);
                return true;
            }
        }
        return false;
    }

    private boolean addBaneModifier(ItemStack stack) {
        if (stack.getItem() instanceof ItemTool || stack.getItem() instanceof ItemArmor) {
            if (stack.hasCapability(CAPABILITY_TOOLMOD, null)) {
                stack.getCapability(CAPABILITY_TOOLMOD, null).setBane(true);
                return true;
            }
        }

        return false;
    }

    private boolean addWingModifier(ItemStack stack) {
        if (stack.getItem() instanceof ItemArmor) {
            stack.getCapability(CAPABILITY_TOOLMOD, null).setWing(true);
            return true;
        }
        return false;
    }

    private boolean addTimeModifier(ItemStack stack) {
        if(Block.getBlockFromItem(stack.getItem()) instanceof BlockForceTorch) {
            handler.setStackInSlot(8, new ItemStack(ModBlocks.timetorch, 1));
            return true;
        }
        return false;
    }

    @Override
    public IFluidTankProperties[] getTankProperties() {
        return new IFluidTankProperties[0];
    }

    @Override
    public int fill(FluidStack resource, boolean doFill) {
        FluidStack resourceCopy = resource.copy();

        if (tank.getFluid() != null) {
            if (tank.getFluid().getFluid() instanceof FluidForce || tank.getFluidAmount() == 0) {
                tank.fill(resourceCopy, true);
            }
        }
        if (tank.getFluid() == null) {
            tank.fill(resourceCopy, true);
        }
        return resource.amount;
    }

    @Nullable
    @Override
    public FluidStack drain(FluidStack resource, boolean doDrain) {
        if (!isFluidEqual(resource))
            return null;
        if (!doDrain) {
            int amount = tank.getFluidAmount() - resource.amount < 0 ? tank.getFluidAmount() : resource.amount;
            return new FluidStack(tank.getFluid(), amount);
        }
        return tank.drain(resource.amount, doDrain);
    }

    @Nullable
    @Override
    public FluidStack drain(int maxDrain, boolean doDrain) {
        return this.tank.drain(maxDrain, doDrain);
    }

    public float getFluidPercentage() {
        return (float) tank.getFluidAmount() / (float) tank.getCapacity();
    }

    public int getFluidGuiHeight(int maxHeight) {
        return (int) Math.ceil(getFluidPercentage() * (float) maxHeight);
    }

    protected boolean isFluidEqual(FluidStack fluid) {
        return isFluidEqual(fluid.getFluid());
    }

    protected boolean isFluidEqual(Fluid fluid) {
        return tank.getFluid().equals(fluid);
    }

}