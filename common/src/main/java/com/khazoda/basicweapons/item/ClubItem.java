package com.khazoda.basicweapons.item;


import com.khazoda.basicweapons.mixinutils.PlayerEntityAccessor;
import com.khazoda.basicweapons.registry.TagRegistry;
import com.khazoda.basicweapons.utils.AllowDenyPass;
import net.minecraft.core.Holder;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.enchantment.Enchantment;

public class ClubItem extends BasicWeaponSweeplessItem {
  public ClubItem(Tier tier, float attackDamage, float attackSpeed, double reach, Item.Properties properties) {
    super(tier, BlockTags.AIR, attackDamage, attackSpeed, reach, properties);
  }

  @Override
  public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
    float attackStrength = 1.0F;
    if (attacker instanceof Player player) {
      attackStrength = ((PlayerEntityAccessor) player).bw$getCooldown(0.5f);
    }
    if (attackStrength > 0.9F) {
      target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 15));
    }
    stack.hurtAndBreak(1, attacker, EquipmentSlot.MAINHAND);
    return true;
  }

  @Override
  public AllowDenyPass bw$canEnchant(ItemStack itemstack, Holder<Enchantment> enchantment) {
    // The item can't be enchanted by enchantments listed here
    return enchantment.is(TagRegistry.SHARPNESS_ENCHANTABLE) || enchantment.is(TagRegistry.SWEEPING_EDGE_ENCHANTABLE) ? AllowDenyPass.DENY : AllowDenyPass.PASS;
  }
}