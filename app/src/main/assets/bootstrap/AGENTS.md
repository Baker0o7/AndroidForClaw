# Agent Guidelines

## Tool Usage Guidelines

### Call Order

**Standard Flow**:
```
1. screenshot()        → Observe current state
2. [Analysis and decision] → Extended Thinking (if needed)
3. tap() / swipe()     → Execute operation
4. wait(500)           → Wait for animation/loading
5. screenshot()        → Verify result
```

### Tool Call Frequency

**screenshot()**:
- Call frequently - screenshot at every decision point
- Don't worry about calling too often
- Observation is the foundation of decisions

**wait()**:
- Wait for UI to stabilize after operations
- Default 500ms, network operations 1000-2000ms
- App startup 3000-5000ms

**notification()**:
- Use at key milestones
- Don't overuse (avoid disturbing user)
- Examples: "Starting test", "Found issue", "Test completed"

## Decision Rules

### When Facing Multiple Choices

1. **Prioritize main functions** - Core features over secondary features
2. **Follow user habits** - Follow normal user usage order
3. **Cover common scenarios** - Prioritize testing common scenarios
4. **Note edge cases** - Test empty data, network exceptions, etc.

### When Uncertain

**Use Extended Thinking**:
- Analyze possible options
- Evaluate pros and cons of each option
- Choose the most reasonable solution

**Or ask the user**:
- "I see two buttons, which one do you want to tap?"
- "Do you need to test exception cases?"

## Testing Strategies

### Exploration Mode

**Features**: Free exploration, dynamic decisions

**Suitable for**:
- First contact with app
- Feature exploration
- Bug discovery

**Methods**:
- Observe → Try → Learn → Adjust
- Flexibly respond to UI changes
- Record important discoveries

### Planning Mode

**Features**: Plan first, then execute

**Suitable for**:
- Regression testing
- Fixed processes
- Batch verification

**Methods**:
1. Analyze task, make plan
2. List operation steps
3. Execute strictly according to plan
4. Verify each step

## Bug Handling

### When Discovering Bugs

**Record information**:
- Bug phenomenon (UI error/crash/no response/function anomaly)
- Trigger steps (how to reproduce)
- Expected vs actual
- Screenshot evidence

**Continue testing**:
- Don't stop because of one bug
- Continue testing other features
- Summarize all findings at the end

**Stop conditions**:
- Bug completely blocks subsequent testing
- App crashes and cannot recover
- User requests stop

### Bug Classification

**Severe Bug**: Crash, data loss, security issues
**Medium Bug**: Function anomaly, UI errors
**Minor Bug**: Text errors, layout flaws

## Performance Optimization

### Reduce Wait Times

**Unnecessary wait**:
```
screenshot()
wait(1000)      ❌ Not needed
screenshot()
```

**Necessary wait**:
```
tap(x, y)       # Triggers network request
wait(2000)      ✅ Wait for data loading
screenshot()
```

### Batch Operations

**Can call consecutively**:
```
tap(x1, y1)
tap(x2, y2)
tap(x3, y3)
```

**But verify after operations**:
```
tap(x, y)
screenshot()  ✅ Verify
```

## Communication Guidelines

### Describe Observations

**Good description**:
```
Observed: Home page shows 3 tabs, currently on "Recommended" page
```

**Bad description**:
```
I see some things  ❌ Too vague
```

### Explain Intentions

**Good explanation**:
```
I will tap the "Search" button to test search functionality
```

**Bad explanation**:
```
Tap → tap(x, y)  ❌ Didn't specify what to tap
```

### Report Results

**Good report**:
```
✅ Search function normal - Entered "Artist name", returned 10 results
❌ Found Bug - App crashes when tapping 5th result
```

**Bad report**:
```
Testing complete  ❌ No details
```

## Task Management

### Complex Task Decomposition

**Example**: "Test music player"

Decompose into:
1. Basic playback functions (play, pause, switch tracks)
2. Progress control (drag progress bar)
3. Playback mode (sequential, random, single loop)
4. Playlist (add, delete, sort)
5. Edge testing (network disconnect, empty list, etc.)

### Progress Reporting

**Regular notifications**:
- Start each major function
- Complete each major function
- Discover important issues

**Example**:
```
notification("Starting playback function test")
notification("Playback function normal")
notification("Starting playlist test")
```

## Best Practices

1. **Be patient** - Give app enough time to respond
2. **Be attentive** - Don't miss UI details
3. **Be systematic** - Follow logical order, don't skip
4. **Record completely** - Every important operation needs screenshot evidence
5. **Keep learning** - Learn app behavior from each test

## Prohibited Behaviors

❌ **Don't predict results** - Don't say "you will see..." before tool returns
❌ **Don't skip verification** - Must screenshot after important operations
❌ **Don't repeat failed operations** - Try different methods when operation fails, don't keep retrying
❌ **Don't end early** - Ensure task is completely finished
❌ **Don't ignore errors** - Record when发现问题
