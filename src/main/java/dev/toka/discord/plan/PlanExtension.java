package dev.toka.discord.plan;
import com.djrapitops.plan.capability.CapabilityService;
import com.djrapitops.plan.extension.*;
import com.djrapitops.plan.extension.annotation.*;
import com.djrapitops.plan.extension.builder.ExtensionDataBuilder;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Function;
@PluginInfo(name="tokaDiscordLink",iconName="link")
public final class PlanExtension implements DataExtension,AutoCloseable {
 private final Predicate<UUID> bound;private final Function<String,String> text;private volatile boolean active=true;
 private ExtensionService registered;
 public PlanExtension(Predicate<UUID> bound,Function<String,String> text){this.bound=bound;this.text=text;}
 public boolean register(){if(!CapabilityService.getInstance().hasCapability("DATA_EXTENSION_BUILDER_API"))return false;
  ExtensionService service=ExtensionService.getInstance();if(service.register(this).isEmpty())return false;registered=service;return true;}
 public boolean snapshot(UUID id){if(!active)throw new NotReadyException();return bound.test(id);}
 @Override public CallEvents[] callExtensionMethodsOn(){return new CallEvents[]{CallEvents.PLAYER_JOIN,CallEvents.PLAYER_LEAVE,CallEvents.PLAYER_PERIODICAL};}
 @DataBuilderProvider public ExtensionDataBuilder player(UUID id){var b=newExtensionDataBuilder();
  return b.addValue(Boolean.class,b.valueBuilder(text.apply("plan.bound")).buildBoolean(snapshot(id)));}
 @Override public void close(){active=false;if(registered!=null){registered.unregister(this);registered=null;}}
}
