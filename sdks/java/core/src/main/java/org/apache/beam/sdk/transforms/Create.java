/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.transforms;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import javax.annotation.Nullable;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.coders.CannotProvideCoderException;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.CoderException;
import org.apache.beam.sdk.coders.CoderRegistry;
import org.apache.beam.sdk.coders.VoidCoder;
import org.apache.beam.sdk.io.BoundedSource;
import org.apache.beam.sdk.io.OffsetBasedSource;
import org.apache.beam.sdk.io.OffsetBasedSource.OffsetBasedReader;
import org.apache.beam.sdk.io.Read;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.util.CoderUtils;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.TimestampedValue;
import org.apache.beam.sdk.values.TimestampedValue.TimestampedValueCoder;
import org.apache.beam.sdk.values.TypeDescriptor;
import org.joda.time.Instant;

/**
 * {@code Create<T>} takes a collection of elements of type {@code T}
 * known when the pipeline is constructed and returns a
 * {@code PCollection<T>} containing the elements.
 *
 * <p>Example of use:
 * <pre> {@code
 * Pipeline p = ...;
 *
 * PCollection<Integer> pc = p.apply(Create.of(3, 4, 5).withCoder(BigEndianIntegerCoder.of()));
 *
 * Map<String, Integer> map = ...;
 * PCollection<KV<String, Integer>> pt =
 *     p.apply(Create.of(map)
 *      .withCoder(KvCoder.of(StringUtf8Coder.of(),
 *                            BigEndianIntegerCoder.of())));
 * } </pre>
 *
 * <p>{@code Create} can automatically determine the {@code Coder} to use
 * if all elements have the same run-time class, and a default coder is registered for that
 * class. See {@link CoderRegistry} for details on how defaults are determined.
 *
 * <p>If a coder can not be inferred, {@link Create.Values#withCoder} must be called
 * explicitly to set the encoding of the resulting
 * {@code PCollection}.
 *
 * <p>A good use for {@code Create} is when a {@code PCollection}
 * needs to be created without dependencies on files or other external
 * entities.  This is especially useful during testing.
 *
 * <p>Caveat: {@code Create} only supports small in-memory datasets.
 *
 * @param <T> the type of the elements of the resulting {@code PCollection}
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public class Create<T> {
  /**
   * Returns a new {@code Create.Values} transform that produces a
   * {@link PCollection} containing elements of the provided
   * {@code Iterable}.
   *
   * <p>The argument should not be modified after this is called.
   *
   * <p>The elements of the output {@link PCollection} will have a timestamp of negative infinity,
   * see {@link Create#timestamped} for a way of creating a {@code PCollection} with timestamped
   * elements.
   *
   * <p>By default, {@code Create.Values} can automatically determine the {@code Coder} to use
   * if all elements have the same non-parameterized run-time class, and a default coder is
   * registered for that class. See {@link CoderRegistry} for details on how defaults are
   * determined.
   * Otherwise, use {@link Create.Values#withCoder} to set the coder explicitly.
   */
  public static <T> Values<T> of(Iterable<T> elems) {
    return new Values<>(elems, Optional.<Coder<T>>absent(), Optional.<TypeDescriptor<T>>absent());
  }

  /**
   * Returns a new {@code Create.Values} transform that produces a
   * {@link PCollection} containing the specified elements.
   *
   * <p>The elements will have a timestamp of negative infinity, see
   * {@link Create#timestamped} for a way of creating a {@code PCollection}
   * with timestamped elements.
   *
   * <p>The arguments should not be modified after this is called.
   *
   * <p>By default, {@code Create.Values} can automatically determine the {@code Coder} to use
   * if all elements have the same non-parameterized run-time class, and a default coder is
   * registered for that class. See {@link CoderRegistry} for details on how defaults are
   * determined.
   * Otherwise, use {@link Create.Values#withCoder} to set the coder explicitly.
   */
  @SafeVarargs
  public static <T> Values<T> of(T elem, T... elems) {
    // This can't be an ImmutableList, as it may accept nulls
    List<T> input = new ArrayList<>(elems.length + 1);
    input.add(elem);
    input.addAll(Arrays.asList(elems));
    return of(input);
  }

  /**
   * Returns a new {@code Create.Values} transform that produces
   * an empty {@link PCollection}.
   *
   * <p>The elements will have a timestamp of negative infinity, see
   * {@link Create#timestamped} for a way of creating a {@code PCollection}
   * with timestamped elements.
   *
   * <p>Since there are no elements, the {@code Coder} cannot be automatically determined.
   * Instead, the {@code Coder} is provided via the {@code coder} argument.
   */
  public static <T> Values<T> empty(Coder<T> coder) {
    return new Values<>(new ArrayList<T>(), Optional.of(coder),
        Optional.<TypeDescriptor<T>>absent());
  }

  /**
   * Returns a new {@code Create.Values} transform that produces
   * an empty {@link PCollection}.
   *
   * <p>The elements will have a timestamp of negative infinity, see
   * {@link Create#timestamped} for a way of creating a {@code PCollection}
   * with timestamped elements.
   *
   * <p>Since there are no elements, the {@code Coder} cannot be automatically determined.
   * Instead, the {@code Coder} is determined from given {@code TypeDescriptor<T>}.
   * Note that a default coder must be registered for the class described in the
   * {@code TypeDescriptor<T>}.
   */
  public static <T> Values<T> empty(TypeDescriptor<T> type) {
    return new Values<>(new ArrayList<T>(), Optional.<Coder<T>>absent(), Optional.of(type));
  }

  /**
   * Returns a new {@code Create.Values} transform that produces a
   * {@link PCollection} of {@link KV}s corresponding to the keys and
   * values of the specified {@code Map}.
   *
   * <p>The elements will have a timestamp of negative infinity, see
   * {@link Create#timestamped} for a way of creating a {@code PCollection}
   * with timestamped elements.
   *
   * <p>By default, {@code Create.Values} can automatically determine the {@code Coder} to use
   * if all elements have the same non-parameterized run-time class, and a default coder is
   * registered for that class. See {@link CoderRegistry} for details on how defaults are
   * determined.
   * Otherwise, use {@link Create.Values#withCoder} to set the coder explicitly.
   */
  public static <K, V> Values<KV<K, V>> of(Map<K, V> elems) {
    List<KV<K, V>> kvs = new ArrayList<>(elems.size());
    for (Map.Entry<K, V> entry : elems.entrySet()) {
      kvs.add(KV.of(entry.getKey(), entry.getValue()));
    }
    return of(kvs);
  }

  /**
   * Returns a new {@link Create.TimestampedValues} transform that produces a
   * {@link PCollection} containing the elements of the provided {@code Iterable}
   * with the specified timestamps.
   *
   * <p>The argument should not be modified after this is called.
   *
   * <p>By default, {@code Create.TimestampedValues} can automatically determine the {@code Coder}
   * to use if all elements have the same non-parameterized run-time class, and a default coder is
   * registered for that class. See {@link CoderRegistry} for details on how defaults are
   * determined.
   * Otherwise, use {@link Create.TimestampedValues#withCoder} to set the coder explicitly.
   */
  public static <T> TimestampedValues<T> timestamped(Iterable<TimestampedValue<T>> elems) {
    return new TimestampedValues<>(
        elems,
        Optional.<Coder<T>>absent(),
        Optional.<TypeDescriptor<T>>absent());
  }

  /**
   * Returns a new {@link Create.TimestampedValues} transform that produces a {@link PCollection}
   * containing the specified elements with the specified timestamps.
   *
   * <p>The arguments should not be modified after this is called.
   */
  @SafeVarargs
  public static <T> TimestampedValues<T> timestamped(
      TimestampedValue<T> elem, @SuppressWarnings("unchecked") TimestampedValue<T>... elems) {
    return timestamped(ImmutableList.<TimestampedValue<T>>builder().add(elem).add(elems).build());
  }

  /**
   * Returns a new root transform that produces a {@link PCollection} containing
   * the specified elements with the specified timestamps.
   *
   * <p>The arguments should not be modified after this is called.
   *
   * <p>By default, {@code Create.TimestampedValues} can automatically determine the {@code Coder}
   * to use if all elements have the same non-parameterized run-time class, and a default coder
   * is registered for that class. See {@link CoderRegistry} for details on how defaults are
   * determined.
   * Otherwise, use {@link Create.TimestampedValues#withCoder} to set the coder explicitly.

   * @throws IllegalArgumentException if there are a different number of values
   * and timestamps
   */
  public static <T> TimestampedValues<T> timestamped(
      Iterable<T> values, Iterable<Long> timestamps) {
    List<TimestampedValue<T>> elems = new ArrayList<>();
    Iterator<T> valueIter = values.iterator();
    Iterator<Long> timestampIter = timestamps.iterator();
    while (valueIter.hasNext() && timestampIter.hasNext()) {
      elems.add(TimestampedValue.of(valueIter.next(), new Instant(timestampIter.next())));
    }
    checkArgument(
        !valueIter.hasNext() && !timestampIter.hasNext(),
        "Expect sizes of values and timestamps are same.");
    return timestamped(elems);
  }

  /////////////////////////////////////////////////////////////////////////////

  /**
   * A {@code PTransform} that creates a {@code PCollection} from a set of in-memory objects.
   */
  public static class Values<T> extends PTransform<PBegin, PCollection<T>> {
    /**
     * Returns a {@link Create.Values} PTransform like this one that uses the given
     * {@code Coder<T>} to decode each of the objects into a
     * value of type {@code T}.
     *
     * <p>By default, {@code Create.Values} can automatically determine the {@code Coder} to use
     * if all elements have the same non-parameterized run-time class, and a default coder is
     * registered for that class. See {@link CoderRegistry} for details on how defaults are
     * determined.
     *
     * <p>Note that for {@link Create.Values} with no elements, the {@link VoidCoder} is used.
     */
    public Values<T> withCoder(Coder<T> coder) {
      return new Values<>(elems, Optional.of(coder), typeDescriptor);
    }

    /**
     * Returns a {@link Create.Values} PTransform like this one that uses the given
     * {@code TypeDescriptor<T>} to determine the {@code Coder} to use to decode each of the
     * objects into a value of type {@code T}. Note that a default coder must be registered for the
     * class described in the {@code TypeDescriptor<T>}.
     *
     * <p>By default, {@code Create.Values} can automatically determine the {@code Coder} to use
     * if all elements have the same non-parameterized run-time class, and a default coder is
     * registered for that class. See {@link CoderRegistry} for details on how defaults are
     * determined.
     *
     * <p>Note that for {@link Create.Values} with no elements, the {@link VoidCoder} is used.
     */
    public Values<T> withType(TypeDescriptor<T> type) {
      return new Values<>(elems, coder, Optional.of(type));
    }

    public Iterable<T> getElements() {
      return elems;
    }

    @Override
    public PCollection<T> expand(PBegin input) {
      try {
        Coder<T> coder = getDefaultOutputCoder(input);
        try {
          CreateSource<T> source = CreateSource.fromIterable(elems, coder);
          return input.getPipeline().apply(Read.from(source));
        } catch (IOException e) {
          throw new RuntimeException(
              String.format("Unable to apply Create %s using Coder %s.", this, coder), e);
        }
      } catch (CannotProvideCoderException e) {
        throw new IllegalArgumentException("Unable to infer a coder and no Coder was specified. "
            + "Please set a coder by invoking Create.withCoder() explicitly.", e);
      }
    }

    @Override
    public Coder<T> getDefaultOutputCoder(PBegin input) throws CannotProvideCoderException {
      if (coder.isPresent()) {
        return coder.get();
      } else if (typeDescriptor.isPresent()) {
        return input.getPipeline().getCoderRegistry().getDefaultCoder(typeDescriptor.get());
      } else {
        return getDefaultCreateCoder(input.getPipeline().getCoderRegistry(), elems);
      }
    }

    /////////////////////////////////////////////////////////////////////////////

    /** The elements of the resulting PCollection. */
    private final transient Iterable<T> elems;

    /** The coder used to encode the values to and from a binary representation. */
    private final transient Optional<Coder<T>> coder;

    /** The value type. */
    private final transient Optional<TypeDescriptor<T>> typeDescriptor;

    /**
     * Constructs a {@code Create.Values} transform that produces a
     * {@link PCollection} containing the specified elements.
     *
     * <p>The arguments should not be modified after this is called.
     */
    private Values(
        Iterable<T> elems,
        Optional<Coder<T>> coder,
        Optional<TypeDescriptor<T>> typeDescriptor) {
      this.elems = elems;
      this.coder = coder;
      this.typeDescriptor = typeDescriptor;
    }

    @VisibleForTesting
    static class CreateSource<T> extends OffsetBasedSource<T> {
      private final List<byte[]> allElementsBytes;
      private final long totalSize;
      private final Coder<T> coder;

      public static <T> CreateSource<T> fromIterable(Iterable<T> elements, Coder<T> elemCoder)
          throws CoderException, IOException {
        ImmutableList.Builder<byte[]> allElementsBytes = ImmutableList.builder();
        long totalSize = 0L;
        for (T element : elements) {
          byte[] bytes = CoderUtils.encodeToByteArray(elemCoder, element);
          allElementsBytes.add(bytes);
          totalSize += bytes.length;
        }
        return new CreateSource<>(allElementsBytes.build(), totalSize, elemCoder);
      }

      /**
       * Create a new source with the specified bytes. The new source owns the input element bytes,
       * which must not be modified after this constructor is called.
       */
      private CreateSource(List<byte[]> elementBytes, long totalSize, Coder<T> coder) {
        super(0, elementBytes.size(), 1);
        this.allElementsBytes = ImmutableList.copyOf(elementBytes);
        this.totalSize = totalSize;
        this.coder = coder;
      }

      @Override
      public long getEstimatedSizeBytes(PipelineOptions options) throws Exception {
        return totalSize;
      }

      @Override
      public BoundedSource.BoundedReader<T> createReader(PipelineOptions options)
          throws IOException {
        return new BytesReader<>(this);
      }

      @Override
      public void validate() {}

      @Override
      public Coder<T> getDefaultOutputCoder() {
        return coder;
      }

      @Override
      public long getMaxEndOffset(PipelineOptions options) throws Exception {
        return allElementsBytes.size();
      }

      @Override
      public OffsetBasedSource<T> createSourceForSubrange(long start, long end) {
        List<byte[]> primaryElems = allElementsBytes.subList((int) start, (int) end);
        long primarySizeEstimate =
            (long) (totalSize * primaryElems.size() / (double) allElementsBytes.size());
        return new CreateSource<>(primaryElems, primarySizeEstimate, coder);
      }

      @Override
      public long getBytesPerOffset() {
        if (allElementsBytes.size() == 0) {
          return 1L;
        }
        return Math.max(1, totalSize / allElementsBytes.size());
      }
    }

    private static class BytesReader<T> extends OffsetBasedReader<T> {
      private int index;
      /**
       * Use an optional to distinguish between null next element (as Optional.absent()) and no next
       * element (next is null).
       */
      @Nullable private Optional<T> next;

      public BytesReader(CreateSource<T> source) {
        super(source);
        index = -1;
      }

      @Override
      @Nullable
      public T getCurrent() throws NoSuchElementException {
        if (next == null) {
          throw new NoSuchElementException();
        }
        return next.orNull();
      }

      @Override
      public void close() throws IOException {}

      @Override
      protected long getCurrentOffset() {
        return index;
      }

      @Override
      protected boolean startImpl() throws IOException {
        return advanceImpl();
      }

      @Override
      public synchronized CreateSource<T> getCurrentSource() {
        return (CreateSource<T>) super.getCurrentSource();
      }

      @Override
      protected boolean advanceImpl() throws IOException {
        CreateSource<T> source = getCurrentSource();
        if (index + 1 >= source.allElementsBytes.size()) {
          next = null;
          return false;
        }
        index++;
        next =
            Optional.fromNullable(
                CoderUtils.decodeFromByteArray(source.coder, source.allElementsBytes.get(index)));
        return true;
      }
    }
  }

  /////////////////////////////////////////////////////////////////////////////

  /**
   * A {@code PTransform} that creates a {@code PCollection} whose elements have
   * associated timestamps.
   */
  public static class TimestampedValues<T> extends PTransform<PBegin, PCollection<T>>{
    /**
     * Returns a {@link Create.TimestampedValues} PTransform like this one that uses the given
     * {@code Coder<T>} to decode each of the objects into a
     * value of type {@code T}.
     *
     * <p>By default, {@code Create.TimestampedValues} can automatically determine the
     * {@code Coder} to use if all elements have the same non-parameterized run-time class,
     * and a default coder is registered for that class. See {@link CoderRegistry} for details
     * on how defaults are determined.
     *
     * <p>Note that for {@link Create.TimestampedValues with no elements}, the {@link VoidCoder}
     * is used.
     */
    public TimestampedValues<T> withCoder(Coder<T> coder) {
      return new TimestampedValues<>(timestampedElements, Optional.of(coder), typeDescriptor);
    }

    /**
     * Returns a {@link Create.TimestampedValues} PTransform like this one that uses the given
     * {@code TypeDescriptor<T>} to determine the {@code Coder} to use to decode each of the
     * objects into a value of type {@code T}. Note that a default coder must be registered for the
     * class described in the {@code TypeDescriptor<T>}.
     *
     * <p>By default, {@code Create.TimestampedValues} can automatically determine the {@code Coder}
     * to use if all elements have the same non-parameterized run-time class, and a default coder is
     * registered for that class. See {@link CoderRegistry} for details on how defaults are
     * determined.
     *
     * <p>Note that for {@link Create.TimestampedValues} with no elements, the {@link VoidCoder} is
     * used.
     */
    public TimestampedValues<T> withType(TypeDescriptor<T> type) {
      return new TimestampedValues<>(timestampedElements, elementCoder, Optional.of(type));
    }

    @Override
    public PCollection<T> expand(PBegin input) {
      try {
        Coder<T> coder = getDefaultOutputCoder(input);

        PCollection<TimestampedValue<T>> intermediate = Pipeline.applyTransform(input,
            Create.of(timestampedElements).withCoder(TimestampedValueCoder.of(coder)));

        PCollection<T> output = intermediate.apply(ParDo.of(new ConvertTimestamps<T>()));
        output.setCoder(coder);
        return output;
      } catch (CannotProvideCoderException e) {
        throw new IllegalArgumentException("Unable to infer a coder and no Coder was specified. "
            + "Please set a coder by invoking CreateTimestamped.withCoder() explicitly.", e);
      }
    }

    /////////////////////////////////////////////////////////////////////////////

    /** The timestamped elements of the resulting PCollection. */
    private final transient Iterable<TimestampedValue<T>> timestampedElements;

    /** The coder used to encode the values to and from a binary representation. */
    private final transient Optional<Coder<T>> elementCoder;

    /** The value type. */
    private final transient Optional<TypeDescriptor<T>> typeDescriptor;

    private TimestampedValues(
        Iterable<TimestampedValue<T>> timestampedElements,
        Optional<Coder<T>> elementCoder,
        Optional<TypeDescriptor<T>> typeDescriptor) {
      this.timestampedElements = timestampedElements;
      this.elementCoder = elementCoder;
      this.typeDescriptor = typeDescriptor;
    }

    private static class ConvertTimestamps<T> extends DoFn<TimestampedValue<T>, T> {
      @ProcessElement
      public void processElement(ProcessContext c) {
        c.outputWithTimestamp(c.element().getValue(), c.element().getTimestamp());
      }
    }

    @Override
    public Coder<T> getDefaultOutputCoder(PBegin input) throws CannotProvideCoderException {
      if (elementCoder.isPresent()) {
        return elementCoder.get();
      } else if (typeDescriptor.isPresent()) {
        return input.getPipeline().getCoderRegistry().getDefaultCoder(typeDescriptor.get());
      } else {
        Iterable<T> rawElements =
            Iterables.transform(
                timestampedElements,
                new Function<TimestampedValue<T>, T>() {
                  @Override
                  public T apply(TimestampedValue<T> input) {
                    return input.getValue();
                  }
                });
        return getDefaultCreateCoder(input.getPipeline().getCoderRegistry(), rawElements);
      }
    }
  }

  private static <T> Coder<T> getDefaultCreateCoder(CoderRegistry registry, Iterable<T> elems)
      throws CannotProvideCoderException {
    checkArgument(
        !Iterables.isEmpty(elems),
        "Elements must be provided to construct the default Create Coder. To Create an empty "
            + "PCollection, either call Create.empty(Coder), or call 'withCoder(Coder)' on the "
            + "result PTransform");
    // First try to deduce a coder using the types of the elements.
    Class<?> elementClazz = Void.class;
    for (T elem : elems) {
      if (elem == null) {
        continue;
      }
      Class<?> clazz = elem.getClass();
      if (elementClazz.equals(Void.class)) {
        elementClazz = clazz;
      } else if (!elementClazz.equals(clazz)) {
        // Elements are not the same type, require a user-specified coder.
        throw new CannotProvideCoderException(
            String.format(
                "Cannot provide coder for %s: The elements are not all of the same class.",
                Create.class.getSimpleName()));
      }
    }

    if (elementClazz.getTypeParameters().length == 0) {
      try {
        @SuppressWarnings("unchecked") // elementClazz is a wildcard type
        Coder<T> coder = (Coder<T>) registry.getDefaultCoder(TypeDescriptor.of(elementClazz));
        return coder;
      } catch (CannotProvideCoderException exc) {
        // Can't get a coder from the class of the elements, try with the elements next
      }
    }

    // If that fails, try to deduce a coder using the elements themselves
    Optional<Coder<T>> coder = Optional.absent();
    for (T elem : elems) {
      Coder<T> c = registry.getDefaultCoder(elem);
      if (!coder.isPresent()) {
        coder = Optional.of(c);
      } else if (!Objects.equals(c, coder.get())) {
        throw new CannotProvideCoderException(
            "Cannot provide coder for elements of "
                + Create.class.getSimpleName()
                + ":"
                + " For their common class, no coder could be provided."
                + " Based on their values, they do not all default to the same Coder.");
      }
    }

    if (!coder.isPresent()) {
      throw new CannotProvideCoderException(
          "Unable to infer a coder. Please register " + "a coder for ");
    }
    return coder.get();
  }
}
