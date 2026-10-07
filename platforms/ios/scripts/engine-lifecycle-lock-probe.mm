#import <Foundation/Foundation.h>
#import "RimeMobile.h"
#include <rime_api.h>
#include <cerrno>
#include <fcntl.h>
#include <spawn.h>
#include <sys/wait.h>
#include <unistd.h>

extern char **environ;

// A fresh exec is required: fcntl locks are process-owned, so a second descriptor
// in the engine process cannot prove that the extension released its file lock.
static int tryLockInChild(NSString *executable, NSString *path) {
    pid_t child;
    char *args[] = {const_cast<char *>(executable.fileSystemRepresentation),
                    const_cast<char *>("--try-lock"),
                    const_cast<char *>(path.fileSystemRepresentation), nullptr};
    if (posix_spawn(&child, args[0], nullptr, nullptr, args, environ)) return 2;
    int status = 0;
    if (waitpid(child, &status, 0) != child || !WIFEXITED(status)) return 2;
    return WEXITSTATUS(status);
}

static NSArray<NSNumber *> *lockResults(NSString *executable, NSString *user) {
    NSMutableArray<NSNumber *> *result = [NSMutableArray array];
    NSDirectoryEnumerator *files = [NSFileManager.defaultManager enumeratorAtPath:user];
    for (NSString *path in files) {
        if ([path.lastPathComponent isEqualToString:@"LOCK"])
            [result addObject:@(tryLockInChild(executable, [user stringByAppendingPathComponent:path]))];
    }
    return result;
}

static int verifyLearningInChild(NSString *executable, NSString *resources, NSString *user, NSString *expected) {
    pid_t child;
    char *args[] = {const_cast<char *>(executable.fileSystemRepresentation),
                    const_cast<char *>("--verify-learning"),
                    const_cast<char *>(resources.fileSystemRepresentation),
                    const_cast<char *>(user.fileSystemRepresentation),
                    const_cast<char *>(expected.UTF8String), nullptr};
    if (posix_spawn(&child, args[0], nullptr, nullptr, args, environ)) return 2;
    int status = 0;
    if (waitpid(child, &status, 0) != child || !WIFEXITED(status)) return 2;
    return WEXITSTATUS(status);
}

static BOOL everyLockEquals(NSArray<NSNumber *> *values, int expected) {
    if (!values.count) return NO;
    for (NSNumber *value in values) if (value.intValue != expected) return NO;
    return YES;
}

static NSDictionary *enter(RimeMobile *engine, NSString *code) {
    NSDictionary *state = @{};
    for (NSUInteger index = 0; index < code.length; ++index)
        state = [engine processKey:[code characterAtIndex:index]];
    return state;
}

int main(int argc, const char **argv) {
    @autoreleasepool {
        if (argc == 3 && strcmp(argv[1], "--try-lock") == 0) {
            int fd = open(argv[2], O_RDWR);
            if (fd < 0) return 2;
            struct flock lock = {};
            lock.l_type = F_WRLCK; lock.l_whence = SEEK_SET;
            int result = fcntl(fd, F_SETLK, &lock), error = errno;
            close(fd);
            return result == 0 ? 0 : ((error == EAGAIN || error == EACCES) ? 1 : 2);
        }
        if (argc == 5 && strcmp(argv[1], "--verify-learning") == 0) {
            RimeMobile *reader = [[RimeMobile alloc] initWithResources:[NSString stringWithUTF8String:argv[2]]
                                                       userDirectory:[NSString stringWithUTF8String:argv[3]]];
            if (!reader || ![reader selectSchema:@"rimes_pinyin"]) return 2;
            NSArray *values = enter(reader, @"ni")[@"candidates"];
            BOOL passed = values.count && [values[0] isEqualToString:[NSString stringWithUTF8String:argv[4]]];
            [reader suspend];
            return passed ? 0 : 1;
        }
        if (argc != 3) return 2;
        NSString *resources = [NSString stringWithUTF8String:argv[1]];
        NSString *root = [NSString stringWithUTF8String:argv[2]];
        NSString *executable = NSProcessInfo.processInfo.arguments[0];
        if (![executable hasPrefix:@"/"])
            executable = [NSFileManager.defaultManager.currentDirectoryPath stringByAppendingPathComponent:executable];
        // Refuse an existing directory: the probe must never touch a real user DB.
        if ([NSFileManager.defaultManager fileExistsAtPath:root]) return 2;
        NSString *user = [root stringByAppendingPathComponent:@"primary-user"];
        NSString *otherUser = [root stringByAppendingPathComponent:@"other-user"];
        if (![NSFileManager.defaultManager createDirectoryAtPath:user withIntermediateDirectories:YES attributes:nil error:nil] ||
            ![NSFileManager.defaultManager createDirectoryAtPath:otherUser withIntermediateDirectories:YES attributes:nil error:nil]) return 2;

        NSMutableArray *checks = [NSMutableArray array];
        auto record = [&](NSString *name, BOOL passed, NSDictionary *details = @{}) {
            NSMutableDictionary *row = [details mutableCopy];
            row[@"name"] = name; row[@"passed"] = @(passed); [checks addObject:row];
        };
        auto checkLocks = [&](NSString *name, NSString *directory, int expected) {
            NSArray *values = lockResults(executable, directory);
            record(name, everyLockEquals(values, expected), @{@"lockCount": @(values.count), @"childResults": values, @"expected": @(expected)});
        };

        RimeMobile *engine = [[RimeMobile alloc] initWithResources:resources userDirectory:user];
        record(@"select built-in pinyin", engine && [engine selectSchema:@"rimes_pinyin"]);
        NSDictionary *state = enter(engine, @"ni");
        NSArray *candidates = state[@"candidates"];
        if (!engine || candidates.count < 2) return 3;
        checkLocks(@"active session holds user dictionary lock", user, 1);
        [engine clear];
        checkLocks(@"clear composition still holds lock (baseline)", user, 1);

        state = enter(engine, @"ni"); candidates = state[@"candidates"];
        NSString *learned = candidates[1];
        state = [engine selectCandidate:1];
        record(@"commit non-first synthetic candidate", [state[@"commit"] isEqualToString:learned]);
        [engine clear];
        state = enter(engine, @"ni");
        record(@"selected candidate is learned", [state[@"candidates"][0] isEqualToString:learned]);
        [engine suspend]; [engine suspend];
        record(@"repeated suspension drops raw composition", !engine.rawInput.length);
        checkLocks(@"suspend releases lock to another process", user, 0);
        int learnedInChild = verifyLearningInChild(executable, resources, user, learned);
        record(@"fresh process reads persisted learned order", learnedInChild == 0, @{@"childResult": @(learnedInChild)});
        state = enter(engine, @"ni");
        record(@"lazy session recreation preserves learned order", [state[@"candidates"][0] isEqualToString:learned]);
        checkLocks(@"resumed session reacquires lock", user, 1);
        record(@"resumed candidate commits without replay", [[engine selectCandidate:0][@"commit"] isEqualToString:learned]);
        [engine suspend];

        record(@"select double pinyin", [engine selectSchema:@"rimes_ziranma"]);
        enter(engine, @"ni"); [engine suspend];
        state = enter(engine, @"nihk");
        NSUInteger index = [state[@"candidates"] indexOfObject:@"你好"];
        record(@"selected double-pinyin schema is restored", index != NSNotFound);
        if (index != NSNotFound) record(@"double-pinyin commit after resume", [[engine selectCandidate:index][@"commit"] isEqualToString:@"你好"]);
        for (NSUInteger cycle = 0; cycle < 10; ++cycle) {
            [engine suspend];
            checkLocks([NSString stringWithFormat:@"cycle %lu suspended locks", (unsigned long)cycle], user, 0);
            state = enter(engine, @"nihk");
            record([NSString stringWithFormat:@"cycle %lu restored input", (unsigned long)cycle], [state[@"candidates"] containsObject:@"你好"]);
        }

        [engine clear];
        RimeMobile *peer = [[RimeMobile alloc] initWithResources:resources userDirectory:user];
        record(@"second instance selects schema", [peer selectSchema:@"rimes_ziranma"]);
        [engine suspend];
        checkLocks(@"suspending one instance preserves peer lock", user, 1);
        state = enter(peer, @"nihk");
        record(@"peer remains usable", [state[@"candidates"] containsObject:@"你好"]);
        [peer suspend];
        checkLocks(@"last instance releases shared DB lock", user, 0);

        enter(engine, @"nihk");
        RimeMobile *other = [[RimeMobile alloc] initWithResources:resources userDirectory:otherUser];
        record(@"new resource generation selects schema", [other selectSchema:@"rimes_ziranma"]);
        [engine suspend]; [peer suspend];
        checkLocks(@"stale instance cannot retire current generation", otherUser, 1);
        state = enter(other, @"nihk");
        record(@"current generation still accepts input", [state[@"candidates"] containsObject:@"你好"]);
        [other suspend];
        checkLocks(@"current generation releases lock", otherUser, 0);

        state = enter(engine, @"nihk");
        record(@"retired instance can recreate its own resources", [state[@"candidates"] containsObject:@"你好"]);
        [other suspend];
        checkLocks(@"retired other instance cannot release active DB", user, 1);
        [engine suspend];
        checkLocks(@"all primary locks released at end", user, 0);
        checkLocks(@"all secondary locks released at end", otherUser, 0);

        BOOL passed = YES;
        for (NSDictionary *check in checks) passed &= [check[@"passed"] boolValue];
        NSDictionary *report = @{@"passed": @(passed), @"checkCount": @(checks.count), @"checks": checks,
                                 @"runtime": @"macOS host probe of iOS RimeMobile bridge",
                                 @"librimeVersion": [NSString stringWithUTF8String:rime_get_api()->get_version()]};
        NSData *json = [NSJSONSerialization dataWithJSONObject:report options:NSJSONWritingPrettyPrinted | NSJSONWritingSortedKeys error:nil];
        fwrite(json.bytes, 1, json.length, stdout); putchar('\n');
        return passed ? 0 : 1;
    }
}
