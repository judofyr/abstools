%%This file is licensed under the terms of the Modified BSD License.
-module(cog).
-export([start/0,start/1,add_async/4,add_and_notify/3,add_blocking/5]).
-export([process_is_runnable_sync/3,process_is_runnable/2,
         process_is_waiting/2, process_is_waiting_polling/2,
         process_is_blocked/2, process_is_blocked_for_gc/2,
         process_poll_is_ready/2, process_poll_is_not_ready/2]).
-export([inc_ref_count/1,dec_ref_count/1]).
-export([init/1]).
-include_lib("abs_types.hrl").
-record(state,{tasks,running=idle,polling=[],referencers=1,dc=null}).
-record(task,{ref,state=waiting}).

%%Garbage collector callbacks
%%stop_world and resume_world are COG specific
-behaviour(gc).
-export([get_references/1, stop_world/1, resume_world/1]).

%% Terminate recklessly.  Used to shutdown system when clock limit reached (if
%% applicable).  Must be called when cog is stopped for GC.  (See
%% `cog_monitor:advance_clock_or_terminate'.)
-export([kill_recklessly/1]).

%%The COG manages all its tasks in a tree task.
%%
%%It is implented as a kind of state machine server, where the variable running represents the state

%%API

start() ->
    start(null).

start(DC)->
    %% There are two DC refs: the one in state is to handle GC and to create a
    %% copy of the current cog (see start_new_task), the one in the cog
    %% structure itself is for evaluating thisDC().  The main block cog and
    %% DCs themselves currently do not have a DC associated.  In the case of
    %% the main block this is arguably a bug and means we cannot use cost
    %% annotations; the implementation of deployment components is contained
    %% in the standard library, so we can be sure they do not use thisDC().
    Cog = #cog{ref=spawn(cog,init, [DC]),dc=DC},
    gc:register_cog(Cog),
    Cog.

add_async(#cog{ref=Cog},Task,Args,Cookie)->
    announce_new_task(Cog, Task, Args, self(),false,Cookie),
    ok.

add_and_notify(#cog{ref=Cog},Task,Args)->
    announce_new_task(Cog, Task, Args, self(), true,{started, Task}),
    TaskRef=await_start(Task, Args),
    TaskRef.

add_blocking(#cog{ref=Ref},Task,Args,Cog,Stack)->
    %% we don't want task:block since this allowed time advance while creating
    %% an object
    task:block_without_time_advance(Cog),
    announce_new_task(Ref, Task, Args, self(), false,{started, Task}),
    TaskRef=await_start(Task,[Args|Stack]),
    task:acquire_token(Cog,[Args|Stack]),
    TaskRef.

process_is_runnable_sync(#cog{ref=Cog},TaskRef,Stack) ->
    announce_task_state_changed(Cog, TaskRef, runnable, self()),
    task:loop_for_token(Stack, new_state_finished).

process_is_runnable(#cog{ref=Cog},TaskRef) ->
    announce_task_state_changed(Cog, TaskRef, runnable, undef).

process_is_waiting(#cog{ref=Cog},TaskRef) ->
    announce_task_state_changed(Cog, TaskRef, waiting, undef).

process_is_waiting_polling(#cog{ref=Cog},TaskRef) ->
    announce_task_state_changed(Cog, TaskRef, waiting_poll, undef).

process_is_blocked(#cog{ref=Cog},TaskRef) ->
    announce_task_state_changed(Cog, TaskRef, blocked, undef).

process_is_blocked_for_gc(#cog{ref=Cog},TaskRef) ->
    announce_task_state_changed(Cog, TaskRef, blocked_for_gc, undef).

process_poll_is_ready(#cog{ref=Cog}, TaskRef) ->
    Cog ! {TaskRef, true}.

process_poll_is_not_ready(#cog{ref=Cog}, TaskRef) ->
    Cog ! {TaskRef, false}.


%%Garbage collector callbacks

inc_ref_count(#cog{ref=Cog})->
    Cog ! inc_ref_count.

dec_ref_count(#cog{ref=Cog})->
    Cog ! dec_ref_count.

get_references(Cog) ->
    Cog ! {get_references, self()},
    receive
        {Refs, Cog} -> Refs
    end.

stop_world(Cog) ->
    Cog ! {stop_world, gc},
    ok.

resume_world(Cog) ->
    Cog ! {done, gc},
    ok.

kill_recklessly(Cog) ->
    Cog ! die_prematurely,
    ok.

%%Internal

announce_new_task(Cog, Task, Args, Sender, Notify,Cookie) ->
    Cog!{new_task,Task,Args,Sender,Notify,Cookie}.

announce_task_state_changed(Cog, TaskRef, State, Sender) ->
    Cog!{new_state,TaskRef,State,Sender}.




init(DC) ->
    process_flag(trap_exit, true),
    cog_monitor:new_cog(self()),
    Running = receive
                  {stop_world, _Sender} ->
                      gc:cog_stopped(self()),
                      {gc, no_task_schedulable};
                  {gc, ok} ->
                      no_task_schedulable
              end,
    loop(#state{tasks=gb_trees:empty(),running=Running,dc=DC}).

%%No task was ready to execute
loop(S=#state{running=no_task_schedulable,tasks=Tasks})->
    New_State=
        receive
            {stop_world, _Sender} ->
                gc:cog_stopped(self()),
                S#state{running={gc, no_task_schedulable}}
        after 0 ->
                receive
                    {new_state,TaskRef,State,Sender}->
                        cog_monitor:cog_active(self()),
                        NewState=set_task_state(S,TaskRef,State),
                        case Sender of
                            undef -> ok;
                            _ -> Sender!new_state_finished
                        end,
                        NewState;
                    {new_task,Task,Args,Sender,Notify,Cookie}->
                        cog_monitor:cog_active(self()),
                        NewTasks=start_new_task(Tasks,S#state.dc,Task,Args,Sender,Notify,Cookie),
                        S#state{tasks=NewTasks};
                    {'EXIT',R,Reason} ->
                        case Reason of
                            normal -> S;
                            _ -> set_task_state(S#state{running=idle},R,abort)
                        end;
                    inc_ref_count->
                        inc_referencers(S);
                    dec_ref_count->
                        dec_referencers(S);
                    {stop_world, _Sender} ->
                        gc:cog_stopped(self()),
                        S#state{running={gc, no_task_schedulable}}
                end
        end,
    case New_State#state.running of
        {gc, _} -> loop(New_State);
        _ -> loop(New_State#state{running=idle})
    end;

%%No task is running now
loop(S=#state{running=idle,tasks=OldTasks,polling=Polling})->
    New_State=
        receive
            {stop_world, _Sender} ->
                gc:cog_stopped(self()),
                S#state{running={gc, idle}}
        after 0 ->
                receive
                    {new_state,TaskRef,State,Sender}->
                        NewState=set_task_state(S,TaskRef,State),
                        case Sender of
                            undef -> ok;
                            _ -> Sender!new_state_finished
                        end,
                        NewState;
                    {new_task,Task,Args,Sender,Notify,Cookie}->
                        NewTasks=start_new_task(OldTasks,S#state.dc,Task,Args,Sender,Notify,Cookie),
                        S#state{tasks=NewTasks};
                    {'EXIT',R,Reason} ->
                        case Reason of
                            normal -> S;
                            _ -> set_task_state(S,R,abort)
                        end;
                    inc_ref_count->
                        inc_referencers(S);
                    dec_ref_count->
                        dec_referencers(S);
                    {stop_world, _Sender} ->
                        gc:cog_stopped(self()),
                        S#state{running={gc, idle}}
                after 0 ->
                        {Tasks,ReadyToRun}=poll_waiting(OldTasks,Polling),
                        T=get_runnable(Tasks),
                        case T of
                            none-> %None found
                                NewTasks=reset_polled(none,ReadyToRun,Tasks),
                                cog_monitor:cog_idle(self()),
                                S#state{running=no_task_schedulable,tasks=NewTasks};
                            #task{ref=R} -> %Execute T
                                R!token,
                                NewTasks=reset_polled(R,ReadyToRun,Tasks),
                                set_task_state(S#state{running=R,tasks=NewTasks},R,running)
                        end
                end
        end,
    loop(New_State);

%%Running task, wait for return of token
loop(S=#state{running=R,tasks=Tasks}) when is_pid(R)->
    New_State=
        receive
            {stop_world, _Sender} ->
                task:send_stop_for_gc(R),
                S1 = await_task_stop_for_gc(S),
                gc:cog_stopped(self()),
                S1#state{running={gc, S1#state.running}}
        after 0 ->
                receive
                    {new_state,TaskRef,State,Sender}->
                        NewState=set_task_state(S,TaskRef,State),
                        case Sender of
                            undef -> ok;
                            _ -> Sender!new_state_finished
                        end,
                        NewState;
                    {new_task,Task,Args,Sender,Notify,Cookie}->
                        NewTasks=start_new_task(Tasks,S#state.dc,Task,Args,Sender,Notify,Cookie),
                        S#state{tasks=NewTasks};
                    {token,R,Task_state}->
                        set_task_state(S#state{running=idle},R,Task_state);
                    {'EXIT',R,Reason} ->
                        case Reason of
                            normal -> S;
                            _ -> set_task_state(S#state{running=idle},R,abort)
                        end;
                    inc_ref_count->
                        inc_referencers(S);
                    dec_ref_count->
                        dec_referencers(S);
                    {stop_world, _Sender} ->
                        task:send_stop_for_gc(R),
                        S1 = await_task_stop_for_gc(S),
                        gc:cog_stopped(self()),
                        S1#state{running={gc, S1#state.running}}
                end
            end,
    loop(New_State);

loop(S=#state{running={blocked, R},tasks=Tasks}) ->
    New_State=
        receive
            {stop_world, _Sender} ->
                gc:cog_stopped(self()),
                S#state{running={gc, {blocked, R}}}
        after 0 ->
                receive
                    {new_state,TaskRef,State,Sender}->
                        NewState=set_task_state(S,TaskRef,State),
                        case Sender of
                            undef -> ok;
                            _ -> Sender!new_state_finished
                        end,
                        NewState;
                    {new_task,Task,Args,Sender,Notify,Cookie}->
                        NewTasks=start_new_task(Tasks,S#state.dc,Task,Args,Sender,Notify,Cookie),
                        S#state{tasks=NewTasks};
                    {'EXIT',R,Reason} ->
                        case Reason of
                            normal -> S;
                            _ -> set_task_state(S#state{running=idle},R,abort)
                        end;
                    inc_ref_count->
                        inc_referencers(S);
                    dec_ref_count->
                        dec_referencers(S);
                    {stop_world, _Sender} ->
                        gc:cog_stopped(self()),
                        S#state{running={gc, {blocked, R}}}
                end
        end,
    loop(New_State);

loop(S=#state{running={blocked_for_gc, R},tasks=Tasks}) ->
    New_State=
        receive
            {stop_world, _Sender} ->
                gc:cog_stopped(self()),
                S#state{running={gc, {blocked_for_gc, R}}}
        after 0 ->
                receive
                    {new_state,TaskRef,State,Sender}->
                        NewState=set_task_state(S,TaskRef,State),
                        case Sender of
                            undef -> ok;
                            _ -> Sender!new_state_finished
                        end,
                        NewState;
                    {new_task,Task,Args,Sender,Notify,Cookie}->
                        NewTasks=start_new_task(Tasks,S#state.dc,Task,Args,Sender,Notify,Cookie),
                        S#state{tasks=NewTasks};
                    {'EXIT',R,Reason} ->
                        case Reason of
                            normal -> S;
                            _ -> set_task_state(S#state{running=idle},R,abort)
                        end;
                    inc_ref_count->
                        inc_referencers(S);
                    dec_ref_count->
                        dec_referencers(S);
                    {stop_world, _Sender} ->
                        gc:cog_stopped(self()),
                        S#state{running={gc, {blocked_for_gc, R}}}
                end
        end,
    loop(New_State);

%%Garbage collector is running, wait before resuming tasks
loop(S=#state{tasks=Tasks, polling=Polling, running={gc,Old}, referencers=Refs, dc=DC}) ->
    New_State=
        receive
            {get_references, Sender} ->
                DC_for_gc = case DC of
                                null -> [];
                                #object{class=class_ABS_DC_DeploymentComponent,
                                        ref=DCref} -> [{object, DCref}]
                            end,
                Sender !
                    {lists:foldl(fun ordsets:union/2, [],
                                 [DC_for_gc | 
                                  lists:map(fun task:get_references/1,
                                            gb_trees:keys(Tasks))]),
                     self()},
                S;
            {done, gc} ->
                case Refs of
                    0 -> cog_monitor:cog_died(self()),
                         gc:unregister_cog(self()),
                         stop;
                    _ -> S#state{running=Old}
                end;
            die_prematurely ->
                %% FIXME: should we just call exit() on the processes?
                lists:map(fun task:kill_recklessly/1, gb_trees:keys(Tasks)),
                stop;
            inc_ref_count->
                inc_referencers(S);
            dec_ref_count->
                dec_referencers(S)
        end,
    case New_State of
        stop -> ok;
        _ -> loop(New_State)
    end.

start_new_task(TaskTree,DC,Task,Args,Sender,Notify,Cookie)->
    Ref=task:start(#cog{ref=self(),dc=DC},Task,Args),
    case Notify of true -> task:notifyEnd(Ref,Sender);false->ok end,
    case Cookie of
        undef -> ok;
        {future, _, _} -> future:task_started(Sender, Ref, Cookie); % async calls
        {started, _} -> Sender ! {Cookie, Ref}  % run methods, init blocks
    end,
    %% Don't generate "cog idle" event when we create new task - this
    %% causes spurious clock advance
    %% R1=case R of no_task_schedulable -> idle; _ -> R end,
    %% we used to start with state=waiting but that led to spurious clock
    %% advancement (cog sent out idle event before task became runnable).  I
    %% did not see where the task state is actually set to runnable either, so
    %% don't treat state=runnable in the next line as gospel.
    gb_trees:insert(Ref,#task{ref=Ref,state=runnable},TaskTree).


%%Sets state in dictionary, and updates polling list
set_task_state(S=#state{running=TaskRef},TaskRef,blocked) ->
    cog_monitor:cog_blocked(self()),
    S#state{running={blocked, TaskRef}};
set_task_state(S=#state{running=TaskRef},TaskRef,blocked_for_gc) ->
    S#state{running={blocked_for_gc, TaskRef}};
set_task_state(S=#state{running={blocked, TaskRef}}, TaskRef, runnable) ->
    TaskRef ! token,
    S#state{running=TaskRef};
set_task_state(S=#state{running={blocked_for_gc, TaskRef}}, TaskRef, runnable) ->
    TaskRef ! token,
    S#state{running=TaskRef};
set_task_state(S=#state{tasks=TasksOld,polling=PolOld},TaskRef,State)->
    Old=#task{state=OldState}=gb_trees:get(TaskRef,TasksOld),
    New=Old#task{state=State},
    PolNew=case State of
               waiting_poll -> [New|PolOld];
               abort -> lists:delete(Old, PolOld);
               _ when OldState == waiting_poll -> lists:delete(Old, PolOld);
               _ -> PolOld
      end,
    TasksNew=case State of
                 done -> gb_trees:delete(TaskRef,TasksOld);
                 abort -> gb_trees:delete(TaskRef,TasksOld);
                 _ -> gb_trees:update(TaskRef,New,TasksOld)
             end,
    S#state{tasks=TasksNew,polling=PolNew}.

get_runnable(Tasks)->
    get_runnable_i(gb_trees:iterator(Tasks)).


get_runnable_i(It) ->
    case gb_trees:next(It) of     
        {_K,T=#task{state=runnable},_It} -> T;
        {_K,_T,I} -> get_runnable_i(I);
        none -> none
    end.

%% Polls all tasks in the polling list.  Return updated Tasks tree and list of
%% tasks ready to run (used subsequently in `reset_polled').
poll_waiting(Tasks1, Pol) ->
    lists:foreach(fun(#task{ref=R})->  R!check end, Pol),
    {NT,Polled}=lists:foldl(fun (T=#task{ref=R},{Tasks,List}) ->
                    receive {R,true}->
                         {gb_trees:update(R,T#task{state=runnable},Tasks),[T|List]};
                     {R,false}->
                        {Tasks,List}
                    end end ,
                 {Tasks1,[]},Pol),
    {NT,Polled}.


%% Resets state in tree of all tasks that were polled but not chosen to
%% execute.  Returns the updated Tasks tree.
reset_polled(Chosen,Polled,Tasks1) ->
    lists:foldl(fun (T=#task{ref=R},Tasks) ->
                        case R of
                            Chosen ->
                                noop;
                            _->
                                R!wait
                        end,
                        gb_trees:update(R,T,Tasks) end ,
                Tasks1,Polled).

%%Changes reference counts in state
inc_referencers(S=#state{referencers=N}) ->
    S#state{referencers=N+1}.

dec_referencers(S=#state{referencers=N}) ->
    S#state{referencers=N-1}.

%%Awaits task reaching synchronization point, or stop the world prelude
await_task_stop_for_gc(S=#state{running=R}) ->
    New_State = receive
                    {new_state,TaskRef,State,Sender} ->
                        NewState=set_task_state(S,TaskRef,State),
                        case Sender of
                            undef -> ok;
                            _ -> Sender!new_state_finished
                        end,
                        NewState;
                    {token,R,Task_state}->
                        set_task_state(S#state{running=idle},R,Task_state)
                end,
    case New_State#state.running =/= R of
        true ->
            New_State;
        false ->
            await_task_stop_for_gc(New_State)
    end.

%%Awaits task being started
await_start(Task, Args) ->
    receive
        {get_references, Sender} ->
            Sender ! {gc:extract_references(Args), self()},
            await_start(Task, Args);
        {{started,Task},Ref}->
            Ref
    end.