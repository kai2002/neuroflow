<img src="https://raw.githubusercontent.com/zenecture/zenecture-docs/master/neuroflow/newlogo.png" width=480 height=132 />

NeuroFlow is a library to design, train and evaluate Artificial Neural Networks.

# Getting Started

There are three modules:

- core: building blocks for neural networks
- application: plugins, helpers, functionality related to application
- playground: examples with resources

To use NeuroFlow for Scala 2.12.x, add these dependencies to your SBT project:

```scala
libraryDependencies ++= Seq(
  "com.zenecture"   %%   "neuroflow-core"          %   "1.6.0",
  "com.zenecture"   %%   "neuroflow-application"   %   "1.6.0"
)
```

If you are new to Neural Nets, you can read about the core principles here:

  - <a href="http://www.znctr.com/blog/artificial-neural-networks">znctr.com/blog/artificial-neural-networks</a>
  
Seeing code examples is a good way to get started. You may have a look at the playground for basic inspiration.

Neural Nets bring joy into your project, a journey full of experiments. 

# Construction of a Net  

<img src="https://raw.githubusercontent.com/zenecture/zenecture-docs/master/neuroflow/arch.png" width=443 height=320 />

Let's construct the fully connected feed-forward net (FFN) depicted above.

```scala
import neuroflow.core.Activators.Double._
import neuroflow.core._
import neuroflow.dsl._
import neuroflow.nets.cpu.DenseNetwork._

implicit val breeder = neuroflow.core.WeightBreeder[Double].random(-1, 1)

val (g, h) = (Sigmoid, Sigmoid)

val net = Network(
  layout = Vector(2) :: Dense(3, g) :: Dense(1, h) :: SquaredMeanError()
)
```

This gives a fully connected `DenseNetwork` under the `SquaredMeanError` loss function. 
The weights are initialized randomly in range (-1, 1) by `WeightBreeder`. We have predefined activators and 
place a softly firing `Sigmoid` on the cells.

A full model is expressed as a linear `Layout` graph and a `Settings` instance. The layout is 
implemented as a heterogenous list, allowing compile-time checks for valid compositions. For instance, 
a little deeper net, with some rates and rules defined, could look like this:

```scala
val (e, f) = (Linear, ReLU)

val L =
      Vector   (11)           ::
      Dense    ( 3, e)        ::
      Dense   (180, f)        ::
      Dense   (360, f)        ::
      Dense   (420, f)        ::
      Dense   (314, f)        ::
      Dense   (271, f)        :: 
      Dense    (23, f)        ::   Softmax()

val deeperNet = Network(
  layout = L, 
  settings = Settings[Double](
    updateRule = Vanilla(), 
    batchSize = Some(8), 
    iterations = 256,
    learningRate = { 
      case (iter, α) if iter < 128 => 1E-4
      case (_, _)  => 1E-6
    }, 
    precision = 1E-8
  )
)
```

Here, the `Softmax` layer computes loss and gradient, which is backpropped into the last `Dense` layer of the net. 
The `updateRule` defines how weights are updated for gradient descent. The `batchSize` defines how many 
samples are presented per weight update. The `learningRate` is a partial function from current iteration 
and learning rate producing a new learning rate. Training terminates after `iterations`, or if loss 
satisfies `precision`. 

Another important aspect of the net is its numerical type. For example, on the GPU, you might want to work with `Float` instead of `Double`.
The numerical type is set by explicitly annotating it on both the `WeightBreeder` and `Settings` instances.

Have a look at the `Settings` class for the complete list of options.

# Training

Our small `net` is a function `f: X -> Y`. It maps from 2d-vector `X` to 1d-vector `Y`.
There are many functions of this kind to learn out there. Here, we go with the XOR function. 
It is linearly not separable, so we can check whether the net can capture this non-linearity.

To learn, we need to know what it means to be wrong. The `SquaredMeanError` loss function is defined as follows:

    L(X, Y, W) = Σ1/2(Y - net(X, W))²

Where `W` are the weights, `Y` is the target and `net(X, W)` the prediction. The sum `Σ` is taken over all samples and 
the square `²` gives a convex functional form. The XOR-adder is a regression challenge, so the `SquaredMeanError` is the choice. 
Alternatively, for 1-of-K classification, we could use the <a href="http://www.znctr.com/blog/digit-recognition#softmax">`Softmax`</a> loss function.

<img src="https://raw.githubusercontent.com/zenecture/zenecture-docs/master/neuroflow/derivative.png" width=443 height=320 />

<small><em>Example: Derivative for w<sub>8</sub></em></small>

In NeuroFlow, we work with <a href="https://github.com/scalanlp/breeze">Breeze</a>, in particular with `DenseVector[V]` and `DenseMatrix[V]`.
Let's define the XOR training data using in-line vector notation:

```scala
import neuroflow.application.plugin.Notation._

val xs = Seq(->(0.0, 0.0), ->(0.0, 1.0), ->(1.0, 0.0), ->(1.0, 1.0))
val ys = Seq(->(0.0), ->(1.0), ->(1.0), ->(0.0))

/*
  It's the XOR-Function :-).
  Or: the net learns to add binary digits modulo 2.
*/

net.train(xs, ys)
```
And then we call `train` on `net` to start.

# Monitoring

The training progress is printed on console so we can track it.

```bash
[run-main-0] INFO neuroflow.nets.cpu.DenseNetworkDouble - [14.01.2018 22:26:56:188] Training with 4 samples, batch size = 4, batches = 1 ...
[run-main-0] INFO neuroflow.nets.cpu.DenseNetworkDouble - [14.01.2018 22:26:56:351] Iteration 1.1, Avg. Loss = 0,525310, Vector: 0.5253104527125074  
[run-main-0] INFO neuroflow.nets.cpu.DenseNetworkDouble - [14.01.2018 22:26:56:387] Iteration 2.1, Avg. Loss = 0,525220, Vector: 0.5252200280272876  
...
```

One line is printed per iteration, `Iteration a.b` where `a` is the iteration count, `b` is the batch and `Avg. Loss` is the mean of the summed batch loss `Vector`.
The batch count `b` loops, depending on the batch size, whereas the iteration count `a` progresses linearly until training is finished. 

<img src="https://raw.githubusercontent.com/zenecture/zenecture-docs/master/neuroflow/wheel-3.png" width=500 height=242 />

<small><em>Rolling over all samples with mini-batches (M:N).</em></small>

To visualize the loss function, we can iteratively append the `Avg. Loss` to a `file` with `LossFuncOutput`.

```scala
Settings(
  lossFuncOutput = Some(LossFuncOutput(file = Some("~/NF/lossFunc.txt")))
)
```

Now we can use beloved gnuplot:

```bash
gnuplot> set style line 1 lc rgb '#0060ad' lt 1 lw 1 pt 7 ps 0.5 
gnuplot> plot '~/NF/lossFunc.txt' with linespoints ls 1
```

<img src="https://raw.githubusercontent.com/zenecture/zenecture-docs/master/neuroflow/errgraph3.png" width=448 height=321 />

Another way to visualize the training progress is to use the `LossFuncOutput` with a function `action: Double => Unit`.

```scala
Settings(
  lossFuncOutput = Some(LossFuncOutput(action = Some(loss => sendToDashboard(loss))))
)
```

This function gets executed in the background after each iteration, using the `Avg. Loss` as input. 
One example is sending the loss to a real-time TV dashboard.

### Useful JVM args

````bash
-Dorg.slf4j.simpleLogger.defaultLogLevel=debug # for GPU mem infos
-Xmx24G # example to increase heap size
````

# Evaluation

When training is done, the net can be evaluated like a regular function:

```scala
val x = ->(0.0, 1.0)
val result = net(x)
// result: DenseVector(0.9940081702899719)
```

The resulting vector has dimension = 1, as specified for the XOR-example.

### Focusing

We can put focus on a layer to use it as the actual model output.

```scala
import neuroflow.dsl.Implicits._

val L = Vector(23) :: Dense(5, Linear) :: Dense(23, Sigmoid) :: Loss()
val ae = Network(layout = L, settings)

ae.train(xs, ys)
```

For instance, here we have a simple AutoEncoder, it learns the identity, and we are interested
in the 5-dimensional coordinates from the second bottleneck layer. In general, we train under 
some loss func, and when done we evaluate the model at a different layer to give answers.

```scala
val focused = net Ω L.tail.head // focus on 2nd layer
val result = focused(->(0.1, 0.2, ..., 0.23))
println(result.length) // 5
```

The focus `Ω` gives a function, and we can give it a 23-dimensional vector and get a 5-dimensional vector back.
Another scenario where a focus is useful is when weights are initialized before training a model, i. e. the activations 
of the layers can be watched and adjusted to find good values.


# Using GPU

If your graphics card supports nVidia's <a href="https://developer.nvidia.com/cuda-gpus">CUDA</a> (Compute Capability >= 3.0), you can train nets on the GPU, 
which is recommended for large nets with millions of weights and samples. On the contrary, smaller nets are faster to train on CPU, because while NeuroFlow 
is busy copying batches between host and GPU, CPU is already done. 

To enable the GPU, you have to install the CUDA driver and toolkit (0.8.x). Example for Linux (Ubuntu 16.04):

```bash
curl -O http://developer.download.nvidia.com/compute/cuda/repos/ubuntu1604/x86_64/cuda-repo-ubuntu1604_8.0.61-1_amd64.deb
sudo dpkg -i ./cuda-repo-ubuntu1604_8.0.61-1_amd64.deb
sudo apt-get update
sudo apt-get install cuda
sudo apt-get install cuda-toolkit-8-0
``` 

With both driver and toolkit installed, add the <a href="http://jcuda.org">jCUDA</a> dependencies to your SBT project:

```scala
resolvers ++= Seq(
  "neuroflow-libs" at "https://github.com/zenecture/neuroflow-libs/raw/master/"
)
```

Then, you can import a GPU implementation for your model:

```scala
import neuroflow.nets.gpu.DenseNetwork._
```

# Persistence

We can save and load nets with `neuroflow.application.plugin.IO`. The weight matrices are encoded in JSON format.

```scala
import neuroflow.application.plugin.IO._

val file = "/path/to/net.nf"
implicit val breeder = File.weightBreeder[Double](file)
val net = Network(layout, settings)
File.writeWeights(net.weights, file)
val json = Json.writeWeights(net.weights)
```

The implicit `breeder` to construct `net` comes from `File.weightBreeder`.
To save the weights back to `file`, we use `File.writeWeights`. To write into a database, 
we can use `Json.writeWeights` to retrieve a raw JSON string and fire a SQL query with it.

### Waypoints

```scala
Settings(
  waypoint = Some(Waypoint(nth = 3, (iter, weights) => File.writeWeights(weights, s"weights-iter-$iter.nf")))
)
```

It is good practice to use the `Waypoint[V]` option for nets with long training times. The training process can be seen as an 
infinite sampling wheel, and with waypoints we can harvest weights now and then to compute intermediate results. Another reason 
to use it is when something crashes, periodically saved weights allow us to continue training from a recent point. Here, every 
`nth = 3` step, the waypoint function is executed, receiving as input iteration count and a snapshot of the weights, which is 
written to file using `File.writeWeights`.
