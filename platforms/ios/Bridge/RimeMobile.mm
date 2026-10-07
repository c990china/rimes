#import "RimeMobile.h"
#include <rime_api.h>
#include <string>
#include <mutex>
extern "C" const char *rimes_lua_last_error(void);
extern "C" void rimes_lua_clear_error(void);

namespace {
std::recursive_mutex engineMutex;
std::string activeResources, activeUser;
NSUInteger engineGeneration = 0;
bool engineInitialized = false;
const char *runtimeModules[] = {"default", "lua", nullptr};
const char *deploymentModules[] = {"default", "lua", "deployer", nullptr};

void shutdownEngine() {
    if (engineInitialized) rime_get_api()->finalize();
    engineInitialized = false; activeResources.clear(); activeUser.clear(); ++engineGeneration;
}
void activateEngine(NSString *resources, NSString *directory, bool deployment = false) {
    if (engineInitialized && activeResources == resources.UTF8String && activeUser == directory.UTF8String && !deployment) return;
    shutdownEngine();
    activeResources = resources.UTF8String; activeUser = directory.UTF8String;
    std::string prebuilt = activeResources + "/build";
    RIME_STRUCT(RimeTraits, traits);
    traits.shared_data_dir = activeResources.c_str(); traits.user_data_dir = activeUser.c_str();
    traits.prebuilt_data_dir = prebuilt.c_str(); traits.staging_dir = prebuilt.c_str();
    traits.distribution_name = "RIMES iOS"; traits.distribution_code_name = "rimes_ios";
    traits.distribution_version = "0.1"; traits.app_name = "rime.rimes_ios"; traits.min_log_level = 3;
    traits.modules = deployment ? deploymentModules : runtimeModules;
    rime_get_api()->setup(&traits); rime_get_api()->initialize(&traits);
    engineInitialized = true;
}
}


// A process-global engine is serialized. Sessions carry a generation so switching
// an isolated package or deploying in the main app never reuses a retired session.
@implementation RimeMobile {
    RimeSessionId _session;
    NSUInteger _generation;
    NSString *_resources;
    NSString *_directory;
    NSString *_schema;
}
- (instancetype)initWithResources:(NSString *)resources userDirectory:(NSString *)directory {
    self = [super init];
    if (!self) return nil;
    _resources = [resources copy]; _directory = [directory copy];
    std::lock_guard<std::recursive_mutex> lock(engineMutex);
    return [self ensureSession] ? self : nil;
}
- (BOOL)ensureSession {
    activateEngine(_resources, _directory);
    if (_session && _generation == engineGeneration) return YES;
    rimes_lua_clear_error();
    _session = rime_get_api()->create_session(); _generation = engineGeneration;
    if (_session && rimes_lua_last_error() && rimes_lua_last_error()[0]) {
        rime_get_api()->destroy_session(_session); _session = 0; return NO;
    }
    if (_session && _schema) {
        BOOL valid = rime_get_api()->select_schema(_session, _schema.UTF8String);
        if (!valid || (rimes_lua_last_error() && rimes_lua_last_error()[0])) {
            rime_get_api()->destroy_session(_session); _session = 0;
            return NO;
        }
    }
    return _session != 0;
}
- (void)dealloc {
    std::lock_guard<std::recursive_mutex> lock(engineMutex);
    if (_session && _generation == engineGeneration) rime_get_api()->destroy_session(_session);
}
- (BOOL)selectSchema:(NSString *)schema {
    std::lock_guard<std::recursive_mutex> lock(engineMutex);
    if (![self ensureSession]) return NO;
    rime_get_api()->clear_composition(_session);
    rimes_lua_clear_error();
    BOOL selected = rime_get_api()->select_schema(_session, schema.UTF8String);
    if (rimes_lua_last_error() && rimes_lua_last_error()[0]) selected = NO;
    if (selected) _schema = [schema copy];
    return selected;
}
+ (NSDictionary *)deployResources:(NSString *)resources schemaIDs:(NSArray<NSString *> *)schemaIDs {
    std::lock_guard<std::recursive_mutex> lock(engineMutex);
    NSMutableArray *failures = [NSMutableArray array];
    @try {
        try {
            activateEngine(resources, resources, true);
            if (!rime_get_api()->deploy_config_file("default.yaml", "config_version")) [failures addObject:@"default.yaml"];
            for (NSString *schema in schemaIDs) {
                NSString *path = [resources stringByAppendingPathComponent:[schema stringByAppendingString:@".schema.yaml"]];
                if (!rime_get_api()->deploy_schema(path.UTF8String)) [failures addObject:schema];
            }
        } catch (const std::exception &exception) {
            [failures addObject:[NSString stringWithUTF8String:exception.what()] ?: @"Rime deployment failed"];
        } catch (...) { [failures addObject:@"Rime deployment failed"]; }
    } @catch (NSException *exception) { [failures addObject:exception.reason ?: @"Rime deployment failed"]; }
    shutdownEngine();
    return @{@"success": @(failures.count == 0), @"failures": failures};
}
+ (NSDictionary *)validateResources:(NSString *)resources schemaIDs:(NSArray<NSString *> *)schemaIDs {
    std::lock_guard<std::recursive_mutex> lock(engineMutex);
    NSMutableArray *failures = [NSMutableArray array];
    NSString *user = [resources stringByAppendingPathComponent:@".validation-user"];
    [[NSFileManager defaultManager] createDirectoryAtPath:user withIntermediateDirectories:YES attributes:nil error:nil];
    @try {
        try {
            activateEngine(resources, user);
            for (NSString *schema in schemaIDs) {
                rimes_lua_clear_error();
                RimeSessionId session = rime_get_api()->create_session();
                BOOL valid = session && rime_get_api()->select_schema(session, schema.UTF8String);
                NSString *luaError = [NSString stringWithUTF8String:rimes_lua_last_error() ?: ""];
                if (!valid || luaError.length) [failures addObject:[NSString stringWithFormat:@"%@: %@", schema, luaError.length ? luaError : @"无法加载已部署方案"]];
                if (session) rime_get_api()->destroy_session(session);
            }
        } catch (const std::exception &exception) {
            [failures addObject:[NSString stringWithUTF8String:exception.what()] ?: @"Rime validation failed"];
        } catch (...) { [failures addObject:@"Rime validation failed"]; }
    } @catch (NSException *exception) { [failures addObject:exception.reason ?: @"Rime validation failed"]; }
    shutdownEngine();
    [[NSFileManager defaultManager] removeItemAtPath:user error:nil];
    return @{@"success": @(failures.count == 0), @"failures": failures};
}
- (NSString *)lastError {
    std::lock_guard<std::recursive_mutex> lock(engineMutex);
    return [NSString stringWithUTF8String:rimes_lua_last_error() ?: ""] ?: @"";
}
- (NSDictionary *)snapshot {
    NSMutableArray *candidates = [NSMutableArray array], *readings = [NSMutableArray array]; NSString *preedit = @"", *committed = @"";
    RIME_STRUCT(RimeCommit, commit);
    if (rime_get_api()->get_commit(_session, &commit)) {
        if (commit.text) committed = [NSString stringWithUTF8String:commit.text] ?: @"";
        rime_get_api()->free_commit(&commit);
    }
    RIME_STRUCT(RimeContext, context);
    if (rime_get_api()->get_context(_session, &context)) {
        if (context.composition.preedit) preedit = [NSString stringWithUTF8String:context.composition.preedit] ?: @"";
        rime_get_api()->free_context(&context);
    }
    RimeCandidateListIterator iterator = {0};
    if (rime_get_api()->candidate_list_begin(_session, &iterator)) {
        while (candidates.count < 60 && rime_get_api()->candidate_list_next(&iterator)) {
            if (iterator.candidate.text) {
                [candidates addObject:[NSString stringWithUTF8String:iterator.candidate.text] ?: @""];
                [readings addObject:iterator.candidate.comment ? ([NSString stringWithUTF8String:iterator.candidate.comment] ?: @"") : @""];
            }
        }
        rime_get_api()->candidate_list_end(&iterator);
    }
    return @{@"preedit":preedit, @"commit":committed, @"candidates":candidates, @"readings":readings};
}
- (NSDictionary *)processKey:(int32_t)key {
    std::lock_guard<std::recursive_mutex> lock(engineMutex);
    if (![self ensureSession]) return @{@"handled": @NO, @"error": self.lastError};
    rimes_lua_clear_error();
    BOOL handled = rime_get_api()->process_key(_session, key, 0);
    NSMutableDictionary *snapshot = [[self snapshot] mutableCopy]; snapshot[@"handled"] = @(handled);
    if (self.lastError.length) snapshot[@"error"] = self.lastError;
    return snapshot;
}
- (NSDictionary *)selectCandidate:(NSUInteger)index {
    std::lock_guard<std::recursive_mutex> lock(engineMutex);
    if (![self ensureSession]) return @{@"error": self.lastError};
    rimes_lua_clear_error();
    rime_get_api()->select_candidate(_session, index);
    NSMutableDictionary *snapshot = [[self snapshot] mutableCopy];
    if (self.lastError.length) snapshot[@"error"] = self.lastError;
    return snapshot;
}
- (void)clear {
    std::lock_guard<std::recursive_mutex> lock(engineMutex);
    // Clearing a retired view must not switch the active package back.
    if (_session && _generation == engineGeneration) rime_get_api()->clear_composition(_session);
}
- (void)suspend {
    std::lock_guard<std::recursive_mutex> lock(engineMutex);
    // Clearing composition leaves the user database open with its POSIX file
    // lock held. Retire this session before the host can suspend the extension.
    // A stale instance must never destroy a new generation's reused session ID.
    if (_session && _generation == engineGeneration) rime_get_api()->destroy_session(_session);
    _session = 0;
}
- (NSString *)rawInput {
    std::lock_guard<std::recursive_mutex> lock(engineMutex);
    if (!_session || _generation != engineGeneration) return @"";
    const char *input = rime_get_api()->get_input(_session);
    return input ? ([NSString stringWithUTF8String:input] ?: @"") : @"";
}
- (NSUInteger)inputCaret {
    std::lock_guard<std::recursive_mutex> lock(engineMutex);
    return _session && _generation == engineGeneration ? rime_get_api()->get_caret_pos(_session) : 0;
}
@end
